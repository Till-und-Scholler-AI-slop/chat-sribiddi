package com.rft.chat_sribiddi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.RequestMapping;

@ExtendWith(MockitoExtension.class)
class UserSettingsControllerTest {

  private static final String USER_ID = "user-123";

  @Mock private UserSettingsService userSettingsService;

  private UserSettingsController controller;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    RequestUserResolver resolver =
        new RequestUserResolver(new MockEnvironment().withProperty("spring.profiles.active", "dev"));
    controller = new UserSettingsController(userSettingsService, resolver);
    request = new MockHttpServletRequest();
    request.addHeader(RequestUserResolver.USER_ID_HEADER, USER_ID);
  }

  @Test
  void controller_isMappedToSettingsPath() {
    RequestMapping classMapping = UserSettingsController.class.getAnnotation(RequestMapping.class);

    assertThat(classMapping.value()).contains("/settings");
  }

  @Test
  void getSettings_returnsCustomInstructions() {
    when(userSettingsService.getCustomInstructions(USER_ID)).thenReturn(java.util.Optional.of("x"));

    var response = controller.getSettings(null, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(new UserSettingsDTO("x"));
  }

  @Test
  void updateSettings_persistsCustomInstructions() {
    var response = controller.updateSettings(null, request, new UserSettingsDTO("x"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(userSettingsService).saveCustomInstructions(USER_ID, "x");
  }
}
