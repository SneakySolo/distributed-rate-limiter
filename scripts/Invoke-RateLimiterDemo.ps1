[CmdletBinding()]
param(
    [string] $BaseUrl = "http://localhost:8000", # Address where Nginx exposes the application.
    [ValidateSet("otp", "payment")]
    [string] $Endpoint = "otp", # Selects the token-bucket OTP endpoint or leaky-bucket payment endpoint.
    [int] $RequestCount = 220, # Total number of requests to send.
    [ValidateRange(0, 60000)]
    [int] $IntervalMs = 100, # Milliseconds to wait between requests.
    [ValidateRange(50, 60000)]
    [int] $PollIntervalMs = 250, # Delay between payment-status checks while draining the queue.
    [string] $UserId = "demo-user-$([Guid]::NewGuid().ToString('N').Substring(0, 8))", # New user ID keeps each run isolated.
    [switch] $SummaryOnly # Retained for compatibility; live output is still shown.
)

$ErrorActionPreference = "Stop" # Make unexpected PowerShell errors stop the script.

$BaseUrl = $BaseUrl.TrimEnd('/') # Prevent a double slash when building URLs.
$healthUrl = "$BaseUrl/health" # Health-check URL used before sending traffic.
$requestUrl = if ($Endpoint -eq "otp") { "$BaseUrl/otp/send" } else { "$BaseUrl/payment/process" } # Choose the endpoint.
$successStatus = if ($Endpoint -eq "otp") { 200 } else { 202 } # OTP returns 200; queued payments return 202.

Write-Host "Checking $healthUrl ..." -ForegroundColor Cyan # Tell the user what the script is doing.
$health = Invoke-RestMethod -Uri $healthUrl -Method Get # Make a GET request and parse the JSON response.
if ($health.status -ne "UP") {
    throw "The service is not healthy: $($health | ConvertTo-Json -Compress)" # Stop if the service is unavailable.
}

Write-Host "Sending $RequestCount $Endpoint requests for user '$UserId' through $BaseUrl ..." -ForegroundColor Cyan
if ($RequestCount -lt 1) {
    throw "RequestCount must be at least 1." # Reject an invalid request count.
}
$started = [DateTime]::UtcNow # Used later to calculate the total demo duration.

# Windows PowerShell 5.1 does not always load this framework assembly by
# default. PowerShell 7 loads it automatically, but Add-Type is harmless there.
Add-Type -AssemblyName System.Net.Http # Load the .NET HTTP types used below.
$client = New-Object System.Net.Http.HttpClient # Create one reusable HTTP client.
$client.Timeout = [TimeSpan]::FromSeconds(15) # Fail a request instead of waiting forever.

function New-DemoRequest {
    param([int]$Number, [string]$Phase) # Receive the request number and descriptive phase.

    # Create a POST request for the selected endpoint.
    $request = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::Post,
        $requestUrl
    )
    $request.Headers.TryAddWithoutValidation("X-User-Id", $UserId) | Out-Null # Identify the rate-limit bucket.
    # Convert the endpoint payload from a PowerShell object into compact JSON.
    $json = if ($Endpoint -eq "otp") {
        @{ phoneNumber = "9999999999" } | ConvertTo-Json -Compress
    } else {
        @{ amount = "10.00"; currency = "INR" } | ConvertTo-Json -Compress
    }
    # Attach JSON as the request body and mark it as application/json.
    $request.Content = New-Object System.Net.Http.StringContent(
        $json,
        [System.Text.Encoding]::UTF8,
        "application/json"
    )

    # Return both the request object and metadata needed when printing results.
    [PSCustomObject]@{
        Number = $Number
        Phase = $Phase
        Request = $request
    }
}

function Convert-ResponseToResult {
    param($Pending, $Response, [string]$ErrorMessage = "") # Convert .NET response data into one simple object.

    $backend = "" # Nginx places the selected application instance in this header.
    if ($Response -and $Response.Headers.Contains("X-Backend")) {
        $backend = ($Response.Headers.GetValues("X-Backend") | Select-Object -First 1) # Read the backend address.
    }
    $body = ""
    $requestId = ""
    if ($Response -and $Response.Content) {
        $body = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult() # Read the JSON response body.
        if ($body) {
            try { $requestId = [string](($body | ConvertFrom-Json).requestId) } catch { } # Payment responses contain a request ID.
        }
    }
    [PSCustomObject]@{
        Request = $Pending.Number
        Phase = $Pending.Phase
        Status = if ($Response) { [int]$Response.StatusCode } else { 0 }
        Backend = [string]$backend
        RequestId = $requestId
        Body = $body
        Error = $ErrorMessage
    }
}

function Send-DemoRequest {
    param([int]$Number, [string]$Phase) # Send exactly one request synchronously.

    $pending = New-DemoRequest $Number $Phase # Build the HTTP request.
    try {
        $response = $client.SendAsync($pending.Request).GetAwaiter().GetResult() # Send and wait for this request's response.
        Convert-ResponseToResult $pending $response # Convert the response for display and summary counting.
        $response.Dispose() # Release response resources.
    } catch {
        Convert-ResponseToResult $pending $null $_.Exception.Message # Represent transport errors as failed results.
    } finally {
        $pending.Request.Dispose() # Always release the request resources.
    }
}

$results = @() # Stores every request result for the final summary.
$pendingPayments = @{} # Maps payment request IDs to their last known status.
$paymentStatusTasks = @{} # Holds non-blocking status requests currently in flight.

function Write-LiveResult {
    param($Result) # Print one request in a human-readable format.
    $outcome = if ($Result.Status -in @(200, 202)) { "ACCEPTED" } elseif ($Result.Status -eq 429) { "RATE-LIMITED" } elseif ($Result.Status -eq 503) { "UNAVAILABLE" } else { "FAILED" } # Map HTTP status to a label.
    $backend = if ([string]::IsNullOrWhiteSpace($Result.Backend)) { "-" } else { $Result.Backend } # Use '-' when Nginx did not provide a backend.
    $detail = if ($Endpoint -eq "otp" -and $Result.Status -eq 429) {
        try { "retry=$((($Result.Body | ConvertFrom-Json).retryAfterMs))ms" } catch { "retry=?" }
    } elseif ($Endpoint -eq "payment" -and $Result.RequestId) {
        "requestId=$($Result.RequestId)"
    } else { "" }
    Write-Host ("{0:HH:mm:ss.fff}  req={1,4}  backend={2,-24}  http={3,3}  {4,-12} {5}" -f (Get-Date), $Result.Request, $backend, $Result.Status, $outcome, $detail) # Print the live row.
}

function Start-PaymentStatusPolls {
    if ($Endpoint -ne "payment") { return } # OTP has no status endpoint.
    $maxActivePolls = 8 # Limit background status calls so they do not overload the service.
    foreach ($requestId in @($pendingPayments.Keys)) {
        if ($paymentStatusTasks.Count -ge $maxActivePolls) { break }
        if (-not $paymentStatusTasks.ContainsKey($requestId)) {
            $paymentStatusTasks[$requestId] = $client.GetAsync("$BaseUrl/payment/status/$requestId") # Start without waiting.
        }
    }
}

function Process-CompletedPaymentStatusPolls {
    if ($Endpoint -ne "payment") { return } # Nothing to poll for OTP.
    foreach ($requestId in @($paymentStatusTasks.Keys)) {
        $task = $paymentStatusTasks[$requestId]
        if (-not $task.IsCompleted) { continue } # Leave unfinished requests running in the background.
        try {
            $statusResponse = $task.GetAwaiter().GetResult() # Safe now because IsCompleted is true.
            if ($statusResponse.IsSuccessStatusCode) {
                $statusBody = $statusResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                $oldStatus = $pendingPayments[$requestId]
                if ($oldStatus -ne $statusBody.status) {
                    Write-Host ("{0:HH:mm:ss.fff}  payment={1}  status={2} -> {3}" -f (Get-Date), $requestId, $oldStatus, $statusBody.status) -ForegroundColor DarkCyan
                    $pendingPayments[$requestId] = $statusBody.status
                }
                if ($statusBody.status -in @("COMPLETED", "FAILED")) { $pendingPayments.Remove($requestId) }
            }
            $statusResponse.Dispose()
        } catch { }
        $paymentStatusTasks.Remove($requestId) # Allow a fresh poll for QUEUED/PROCESSING payments.
    }
}

Write-Host "Live one-by-one demonstration: interval=${IntervalMs}ms, requests=$RequestCount" -ForegroundColor Yellow # Print run settings.
Write-Host "Request                         Backend                    HTTP  Result       Details" -ForegroundColor Yellow # Print table headings.

for ($number = 1; $number -le $RequestCount; $number++) {
    Process-CompletedPaymentStatusPolls # Print any worker updates that finished since the last request.
    Start-PaymentStatusPolls # Start a few payment status checks without blocking the next request.
    $result = Send-DemoRequest $number "One-by-one stream" # Send one request, never a concurrent burst.
    $results += $result # Add it to the final statistics.
    Write-LiveResult $result # Print the request immediately.
    if ($Endpoint -eq "payment" -and $result.RequestId -and $result.Status -eq 202) {
        $pendingPayments[$result.RequestId] = "QUEUED"
    }
    Process-CompletedPaymentStatusPolls # Print worker progress that completed during the request.
    Start-PaymentStatusPolls # Keep the status-monitoring pipeline full.
    if ($number -lt $RequestCount -and $IntervalMs -gt 0) { Start-Sleep -Milliseconds $IntervalMs } # Control request arrival speed.
}

if ($Endpoint -eq "payment") {
    # The worker drains at about 100 requests/minute. Allow enough time for
    # accepted requests to transition to COMPLETED after the producer stops.
    $deadline = (Get-Date).AddSeconds(120) # Give accepted payments time to finish.
    while ($pendingPayments.Count -gt 0 -and (Get-Date) -lt $deadline) {
        Process-CompletedPaymentStatusPolls
        Start-PaymentStatusPolls
        Start-Sleep -Milliseconds $PollIntervalMs # Avoid a busy loop while waiting for the worker.
    }
}
$client.Dispose() # Close the reusable HTTP client.
$results = $results | Sort-Object Request # Restore request-number order for summary calculations.

if ($SummaryOnly) { Write-Host "(Live request output was enabled; SummaryOnly is retained for compatibility.)" }

$elapsed = ([DateTime]::UtcNow - $started).TotalSeconds # Calculate total elapsed time.
$allowed = @($results | Where-Object Status -eq $successStatus).Count
$limited = @($results | Where-Object Status -eq 429).Count
$unavailable = @($results | Where-Object Status -eq 503).Count
$failed = @($results | Where-Object { $_.Status -notin @($successStatus, 429, 503) }).Count
$backends = @($results | Where-Object Backend | Select-Object -ExpandProperty Backend -Unique)

Write-Host ""
Write-Host "Rate limiter demonstration" -ForegroundColor Green
Write-Host "  Endpoint:       $Endpoint"
Write-Host "  Requests:       $RequestCount"
Write-Host "  One-by-one accepted: $allowed"
Write-Host "  One-by-one rate-limited: $limited"
Write-Host "  Allowed:        $allowed (HTTP $successStatus)"
Write-Host "  Rate limited:   $limited (HTTP 429)"
Write-Host "  Redis/service:  $unavailable (HTTP 503)"
Write-Host "  Other failures: $failed"
Write-Host "  Duration:       $([Math]::Round($elapsed, 2)) seconds"
Write-Host "  Backends:       $($backends.Count) -> $($backends -join ', ')"
Write-Host ""

$results | Group-Object Status | Sort-Object Name | ForEach-Object { # Print a count for every HTTP status.
    Write-Host ("  HTTP {0}: {1} request(s)" -f $_.Name, $_.Count)
}

if ($allowed -eq 0 -or $limited -eq 0 -or $failed -gt 0 -or $unavailable -gt 0) {
    Write-Error "DEMO FAILED: expected successful requests and at least one HTTP 429, with no transport/service failures."
    exit 1 # Return a failure exit code to PowerShell/CI.
}

if ($backends.Count -lt 2) {
    Write-Error "DEMO INCONCLUSIVE: only $($backends.Count) backend instance(s) answered. Run against Docker Compose at http://localhost:8000 and try again."
    exit 2 # Return a distinct inconclusive exit code.
}

Write-Host "PASS: requests were accepted up to bucket capacity, then rate-limited across multiple app instances." -ForegroundColor Green
