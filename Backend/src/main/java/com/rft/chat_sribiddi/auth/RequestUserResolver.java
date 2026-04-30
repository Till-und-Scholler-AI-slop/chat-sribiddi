package com.rft.chat_sribiddi.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class RequestUserResolver {

  public static final String USER_ID_HEADER = "X-Chat-User-Id";

  private final Environment environment;

  public RequestUserResolver(Environment environment) {
    this.environment = environment;
  }

  public Optional<String> resolveUserId(OidcUser principal, HttpServletRequest request) {
    if (principal != null && principal.getSubject() != null && !principal.getSubject().isBlank()) {
      return Optional.of(principal.getSubject());
    }
    if (request == null || !isDevProfileActive()) {
      return Optional.empty();
    }
    return Optional.ofNullable(request.getHeader(USER_ID_HEADER))
        .map(String::trim)
        .filter(userId -> !userId.isEmpty());
  }

  private boolean isDevProfileActive() {
    return environment != null
        && Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equals);
  }
}
