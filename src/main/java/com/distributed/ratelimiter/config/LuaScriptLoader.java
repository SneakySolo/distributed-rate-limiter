package com.distributed.ratelimiter.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/*
 * This is to load our LUA file into algos during runtime
 * also this reads the whole file into a byte array and converts it to a String.
 * The scripts are loaded once at startup and held in memory as plain Java Strings.
*/

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