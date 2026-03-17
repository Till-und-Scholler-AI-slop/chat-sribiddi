package com.rft.chat_sribiddi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.RedisConnectionFailureException;

class CacheConfigTest {

  private CacheErrorHandler errorHandler;
  private Cache cache;

  @BeforeEach
  void setUp() {
    errorHandler = new CacheConfig().errorHandler();
    cache = mock(Cache.class);
    when(cache.getName()).thenReturn("model-catalog");
  }

  @Test
  void errorHandler_isNotNull() {
    assertThat(errorHandler).isNotNull();
  }

  @Test
  void handleCacheGetError_doesNotThrow_onRedisConnectionFailure() {
    RuntimeException ex = new RedisConnectionFailureException("Unable to connect to Redis");
    assertThatCode(() -> errorHandler.handleCacheGetError(ex, cache, "key"))
        .doesNotThrowAnyException();
  }

  @Test
  void handleCachePutError_doesNotThrow_onRedisConnectionFailure() {
    RuntimeException ex = new RedisConnectionFailureException("Unable to connect to Redis");
    assertThatCode(() -> errorHandler.handleCachePutError(ex, cache, "key", "value"))
        .doesNotThrowAnyException();
  }

  @Test
  void handleCacheEvictError_doesNotThrow_onRedisConnectionFailure() {
    RuntimeException ex = new RedisConnectionFailureException("Unable to connect to Redis");
    assertThatCode(() -> errorHandler.handleCacheEvictError(ex, cache, "key"))
        .doesNotThrowAnyException();
  }

  @Test
  void handleCacheClearError_doesNotThrow_onRedisConnectionFailure() {
    RuntimeException ex = new RedisConnectionFailureException("Unable to connect to Redis");
    assertThatCode(() -> errorHandler.handleCacheClearError(ex, cache))
        .doesNotThrowAnyException();
  }
}
