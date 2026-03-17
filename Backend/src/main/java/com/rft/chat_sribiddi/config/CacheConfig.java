package com.rft.chat_sribiddi.config;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

  @Bean
  public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration defaultConfig =
        RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .entryTtl(Duration.ofMinutes(10));

    RedisCacheConfiguration modelCatalogConfig =
        RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .entryTtl(Duration.ofMinutes(5));

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaultConfig)
        .withCacheConfiguration("model-catalog", modelCatalogConfig)
        .build();
  }

  @Override
  public CacheErrorHandler errorHandler() {
    return new CacheErrorHandler() {
      @Override
      public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache get failed for cache='{}': {}", cache.getName(), e.getMessage());
      }

      @Override
      public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
        log.warn("Cache put failed for cache='{}': {}", cache.getName(), e.getMessage());
      }

      @Override
      public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
        log.warn("Cache evict failed for cache='{}': {}", cache.getName(), e.getMessage());
      }

      @Override
      public void handleCacheClearError(RuntimeException e, Cache cache) {
        log.warn("Cache clear failed for cache='{}': {}", cache.getName(), e.getMessage());
      }
    };
  }
}
