package com.rft.chat_sribiddi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class RequestUserResolverTest {

  @Test
  void resolveUserId_prefersAuthenticatedPrincipal() {
    OidcUser principal = org.mockito.Mockito.mock(OidcUser.class);
    HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
    when(principal.getSubject()).thenReturn("oidc-user");
    when(request.getHeader(RequestUserResolver.USER_ID_HEADER)).thenReturn("header-user");

    var resolver = new RequestUserResolver(new MockEnvironment().withProperty("spring.profiles.active", "dev"));

    assertThat(resolver.resolveUserId(principal, request)).contains("oidc-user");
  }

  @Test
  void resolveUserId_ignoresRequestHeaderOutsideDevProfile() {
    HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
    when(request.getHeader(RequestUserResolver.USER_ID_HEADER)).thenReturn("header-user");

    var resolver = new RequestUserResolver(new MockEnvironment());

    assertThat(resolver.resolveUserId(null, request)).isEmpty();
  }

  @Test
  void resolveUserId_allowsRequestHeaderInDevProfile() {
    HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
    when(request.getHeader(RequestUserResolver.USER_ID_HEADER)).thenReturn(" header-user ");

    var resolver = new RequestUserResolver(new MockEnvironment().withProperty("spring.profiles.active", "dev"));

    assertThat(resolver.resolveUserId(null, request)).contains("header-user");
  }
}
