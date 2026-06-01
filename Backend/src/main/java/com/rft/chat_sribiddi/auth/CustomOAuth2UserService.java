package com.rft.chat_sribiddi.auth;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends OidcUserService {
  private final UserRepository userRepository;

  @Override
  public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
    OidcUser oidcUser = super.loadUser(userRequest);

    String providerId = oidcUser.getSubject();

    Optional<User> userOptional = userRepository.findByProviderId(providerId);

    if (userOptional.isEmpty()) {
      User user = new User();
      user.setEmail(oidcUser.getEmail());
      user.setProviderId(providerId);
      try {
        userRepository.saveAndFlush(user);
        log.debug("Created new user for providerId={}", providerId);
      } catch (DataIntegrityViolationException ex) {
        if (userRepository.findByProviderId(providerId).isEmpty()) {
          throw ex;
        }
        log.debug("User for providerId={} was created concurrently", providerId);
      }
    }

    return oidcUser;
  }
}
