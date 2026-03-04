package com.rft.chat_sribiddi.auth.config;

import com.rft.chat_sribiddi.auth.CustomOAuth2UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Slf4j
@Configuration
@EnableWebSecurity
@Profile("!dev")
@RequiredArgsConstructor
public class SecurityConfig {

  private final CustomOAuth2UserService customOAuth2UserService;

  @Value("${app.cors.allowed-origins}")
  private List<String> allowedOrigins;

  @Value("${app.frontend-url:http://localhost:5173}")
  private String frontendUrl;

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.cors(Customizer.withDefaults())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers(
                      "/", "/login", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                  .permitAll();
              auth.anyRequest().authenticated();
            })
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .oauth2Login(
            oauth2 ->
                oauth2
                    .userInfoEndpoint(
                        userInfo -> userInfo.oidcUserService(customOAuth2UserService))
                    .successHandler(new SimpleUrlAuthenticationSuccessHandler(frontendUrl)))
        .logout(logout -> logout.logoutSuccessUrl(frontendUrl))
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    List<String> originPatterns =
        allowedOrigins.stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    configuration.setAllowedOriginPatterns(originPatterns);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
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
