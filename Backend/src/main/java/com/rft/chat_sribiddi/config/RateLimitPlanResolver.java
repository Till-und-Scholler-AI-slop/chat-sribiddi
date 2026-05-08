package com.rft.chat_sribiddi.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.auth.User;
import com.rft.chat_sribiddi.auth.UserRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RateLimitPlanResolver {

  private final UserRepository userRepository;
  private final Cache<String, Plan> planCache =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(1)).build();

  public Plan resolvePlan(String providerId) {
    if (providerId == null || providerId.isBlank()) {
      return Plan.FREE;
    }
    return planCache.get(providerId, this::loadPlan);
  }

  private Plan loadPlan(String providerId) {
    return userRepository.findByProviderId(providerId).map(User::getPlan).orElse(Plan.FREE);
  }
}
