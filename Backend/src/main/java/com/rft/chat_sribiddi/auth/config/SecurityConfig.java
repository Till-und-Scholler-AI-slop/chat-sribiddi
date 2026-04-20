package com.rft.chat_sribiddi.auth.config;

import com.rft.chat_sribiddi.auth.CustomOAuth2UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Configuration
@EnableWebSecurity
@Profile("!dev")
@RequiredArgsConstructor
public class SecurityConfig {

  private final CustomOAuth2UserService customOAuth2UserService;

  @Value("${app.cors.allowed-origins}")
  private List<String> allowedOrigins;

  @Value("${app.frontend-url:http://localhost:3000}")
  private String frontendUrl;

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, OAuth2AuthorizationRequestResolver authorizationRequestResolver) {
    return http.cors(Customizer.withDefaults())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                    // Stripe authenticates webhook calls via the Stripe-Signature header, not a
                    // CSRF token; the endpoint is also called from outside the browser so there
                    // is no XSRF-TOKEN cookie available.
                    .ignoringRequestMatchers("/billing/webhook"))
        .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers(
                      "/",
                      "/login",
                      "/billing/webhook",
                      "/v3/api-docs/**",
                      "/swagger-ui/**",
                      "/swagger-ui.html")
                  .permitAll();
              auth.anyRequest().authenticated();
            })
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .oauth2Login(
            oauth2 ->
                oauth2
                    .authorizationEndpoint(
                        endpoint ->
                            endpoint.authorizationRequestResolver(authorizationRequestResolver))
                    .userInfoEndpoint(
                        userInfo -> userInfo.oidcUserService(customOAuth2UserService))
                    .successHandler(new SimpleUrlAuthenticationSuccessHandler(frontendUrl)))
        .logout(
            logout ->
                logout
                    .logoutUrl("/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                    .logoutSuccessHandler(
                        new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .build();
  }

  /**
   * Authorization request resolver that forces Google's account chooser by adding {@code
   * prompt=select_account} to every authorization request. This allows users to pick a different
   * Google account when logging in again (e.g. after clicking "switch account"), without needing to
   * clear browser data.
   */
  @Bean
  OAuth2AuthorizationRequestResolver authorizationRequestResolver(
      ClientRegistrationRepository clientRegistrationRepository) {
    DefaultOAuth2AuthorizationRequestResolver resolver =
        new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, "/oauth2/authorization");
    resolver.setAuthorizationRequestCustomizer(
        customizer ->
            customizer.additionalParameters(params -> params.put("prompt", "select_account")));
    return resolver;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    List<String> originPatterns =
        allowedOrigins.stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    configuration.setAllowedOriginPatterns(originPatterns);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  /**
   * Forces the deferred CSRF token to be loaded on every response so the {@code XSRF-TOKEN} cookie
   * is written before the SPA attempts any state-mutating request.
   */
  static final class CsrfCookieFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
      if (csrfToken != null) {
        csrfToken.getToken();
      }
      filterChain.doFilter(request, response);
    }
  }

  /**
   * CSRF token request handler for SPA clients. Reads the token from the {@code X-XSRF-TOKEN}
   * header when present, falling back to the default BREACH-protected XOR handler otherwise.
   */
  static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();
    private final XorCsrfTokenRequestAttributeHandler xor =
        new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
        HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
      xor.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
      String header = request.getHeader(csrfToken.getHeaderName());
      if (StringUtils.hasText(header)) {
        return plain.resolveCsrfTokenValue(request, csrfToken);
      }
      return xor.resolveCsrfTokenValue(request, csrfToken);
    }
  }
}
