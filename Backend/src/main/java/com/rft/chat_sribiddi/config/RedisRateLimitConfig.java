package com.rft.chat_sribiddi.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis", matchIfMissing = true)
public class RedisRateLimitConfig {

  @Bean(destroyMethod = "shutdown")
  RedisClient rateLimitRedisClient(DataRedisProperties redisProperties) {
    RedisURI.Builder builder =
        RedisURI.builder()
            .withHost(redisProperties.getHost())
            .withPort(redisProperties.getPort())
            .withDatabase(redisProperties.getDatabase());
    if (redisProperties.getTimeout() != null) {
      builder.withTimeout(redisProperties.getTimeout());
    }
    if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
      builder.withPassword(redisProperties.getPassword().toCharArray());
    }
    return RedisClient.create(builder.build());
  }

  @Bean(destroyMethod = "close")
  StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient rateLimitRedisClient) {
    return rateLimitRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
  }

  @Bean
  ProxyManager<String> rateLimitProxyManager(
      StatefulRedisConnection<String, byte[]> rateLimitRedisConnection,
      RateLimitProperties properties) {
    return Bucket4jLettuce.casBasedBuilder(rateLimitRedisConnection)
        .expirationAfterWrite(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                properties.getBucketTtl()))
        .build();
  }
}
