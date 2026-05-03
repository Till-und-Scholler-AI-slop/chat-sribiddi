package com.rft.chat_sribiddi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.auth.UserSettingsController.BillingModePreferenceDTO;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

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
  void updateBillingMode_isMappedToSettingsBillingModePath() throws Exception {
    RequestMapping classMapping = UserSettingsController.class.getAnnotation(RequestMapping.class);
    PatchMapping methodMapping =
        UserSettingsController.class
            .getMethod(
                "updateBillingMode",
                OidcUser.class,
                HttpServletRequest.class,
                BillingModePreferenceDTO.class)
            .getAnnotation(PatchMapping.class);

    assertThat(classMapping.value()).contains("/settings");
    assertThat(methodMapping.value()).contains("/billing-mode");
  }

  @Test
  void updateBillingMode_persistsAndReturnsMode() {
    when(userSettingsService.saveBillingModePreference(USER_ID, BillingMode.BYOK))
        .thenReturn(BillingMode.BYOK);

    var response =
        controller.updateBillingMode(null, request, new BillingModePreferenceDTO(BillingMode.BYOK));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().billingMode()).isEqualTo(BillingMode.BYOK);
    verify(userSettingsService).saveBillingModePreference(USER_ID, BillingMode.BYOK);
  }

  @Test
  void updateBillingMode_rejectsMissingMode() {
    assertThatThrownBy(() -> controller.updateBillingMode(null, request, null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
