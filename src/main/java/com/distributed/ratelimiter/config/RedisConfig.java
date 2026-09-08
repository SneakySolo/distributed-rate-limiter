package com.distributed.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

     /*
     * This is for JAVA to Redis and back and forth communication
     * RedisTemplate is Spring's wrapper around
     * all Redis operations like strings, hashes, sorted sets, Lua execution, everything
     * The connection details (host, port, pool size) come from application.yml
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>(); // creating an empty RedisTemplate, we'll use this
        template.setConnectionFactory(factory); // this tells the template to use connection factory whenever you need to talk to Redis

        // now Redis stores data as bytes, not Java Strings.
        // so we use a serializer that converts Java objects into bytes
        // these lines makes sure that the Redis values should be stored as Strings
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet(); // initialize template
        return template;
    }
}