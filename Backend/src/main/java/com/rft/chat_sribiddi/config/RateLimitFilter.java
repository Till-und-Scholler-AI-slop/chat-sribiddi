package com.rft.chat_sribiddi.config;

import com.rft.chat_sribiddi.auth.Plan;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimitProperties properties;
  private final RateLimiter rateLimiter;
  private final RateLimitPlanResolver planResolver;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !properties.isEnabled()
        || "OPTIONS".equalsIgnoreCase(request.getMethod())
        || "/billing/webhook".equals(path)
        || "/actuator/health".equals(path)
        || "/actuator/info".equals(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    RateLimit rateLimit = rateLimitForCurrentRequest(request);
    RateLimitDecision decision = rateLimiter.tryConsume(rateLimit.key(), rateLimit.limit());
    if (!decision.allowed()) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
      response.setContentType("application/json");
      response.getWriter().write("{\"message\":\"Too many requests. Please retry later.\"}");
      return;
    }

    filterChain.doFilter(request, response);
  }

  RateLimit rateLimitForCurrentRequest(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.isAuthenticated()
        && !"anonymousUser".equals(authentication.getPrincipal())) {
      Plan plan = planResolver.resolvePlan(authentication.getName());
      return new RateLimit("user:" + authentication.getName(), properties.limitFor(plan));
    }
    return new RateLimit("ip:" + clientIp(request), properties.getAnonymousRequestsPerMinute());
  }

  String clientIp(HttpServletRequest request) {
    String remoteAddress = request.getRemoteAddr();
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (properties.getTrustedProxies().contains(remoteAddress)
        && forwardedFor != null
        && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return remoteAddress;
  }

  record RateLimit(String key, int limit) {}
}
