package com.rft.chat_sribiddi.auth.config;

import com.rft.chat_sribiddi.auth.CustomOAuth2UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Slf4j
@Configuration
@EnableWebSecurity
@Profile("production")
@RequiredArgsConstructor
public class SecurityConfig {

  private final CustomOAuth2UserService customOAuth2UserService;

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) {
    try {
      return http.cors(Customizer.withDefaults())
          .authorizeHttpRequests(
              auth -> {
                auth.requestMatchers("/", "/login", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll();
                auth.anyRequest().authenticated();
              })
          .oauth2Login(
              oauth2 ->
                  oauth2.userInfoEndpoint(
                      userInfo -> userInfo.oidcUserService(customOAuth2UserService)))
          .build();
    } catch (Exception e) {
      log.error("Error in SecurityConfig: {}", e.getMessage());
      return null;
    }
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(List.of("http://localhost:*"));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
