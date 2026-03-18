package com.rft.chat_sribiddi.auth;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends OidcUserService {

  private static final Logger log = LoggerFactory.getLogger(CustomOAuth2UserService.class);
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
      userRepository.save(user);
      log.debug("Created new user for providerId={}", providerId);
    }

    return oidcUser;
  }
}
