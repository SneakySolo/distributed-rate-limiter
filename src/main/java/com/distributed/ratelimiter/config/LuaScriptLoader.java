package com.distributed.ratelimiter.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Component
public class LuaScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(LuaScriptLoader.class);

    private final String tokenBucketScript;
    private final String leakyBucketEnqueueScript;

    public LuaScriptLoader() throws IOException {
        this.tokenBucketScript = loadScript("lua/TokenBucket.lua");
        this.leakyBucketEnqueueScript = loadScript("lua/LeakyBucketEnqueue.lua");
        log.info("Lua scripts loaded from resources");
    }

    private String loadScript(String resourcePath) throws IOException {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            String script = new String(Files.readAllBytes(Paths.get(resource.getFile().getPath())));
            log.debug("Loaded Lua script: {}", resourcePath);
            return script;
        } catch (IOException e) {
            log.error("Failed to load Lua script: {}", resourcePath, e);
            throw e;
        }
    }

    public String getTokenBucketScript() {
        return tokenBucketScript;
    }

    public String getLeakyBucketEnqueueScript() {
        return leakyBucketEnqueueScript;
    }
}