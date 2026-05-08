package com.rft.chat_sribiddi.config;

public interface RateLimiter {

  RateLimitDecision tryConsume(String key, int requestsPerMinute);
}
