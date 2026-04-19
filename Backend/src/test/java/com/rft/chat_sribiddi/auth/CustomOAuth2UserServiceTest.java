package com.rft.chat_sribiddi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomOAuth2UserServiceTest {

  @Test
  void newUser_hasDefaultPlanFree() {
    User user = new User();
    assertThat(user.getPlan()).isEqualTo(Plan.FREE);
  }

  @Test
  void newUser_hasDefaultBillingModeAuto() {
    User user = new User();
    assertThat(user.getBillingModePreference()).isEqualTo(BillingMode.AUTO);
  }

  @Test
  void newUser_nullableFieldsAreNull() {
    User user = new User();
    assertThat(user.getPlanStatus()).isNull();
    assertThat(user.getPlanValidUntil()).isNull();
    assertThat(user.getStripeCustomerId()).isNull();
  }

  @Test
  void newUser_simulateOAuth2Creation_fieldsCorrectlyInitialized() {
    // Simulates the code path in CustomOAuth2UserService.loadUser()
    User user = new User();
    user.setEmail("test@example.com");
    user.setProviderId("google-sub-123");

    assertThat(user.getEmail()).isEqualTo("test@example.com");
    assertThat(user.getProviderId()).isEqualTo("google-sub-123");
    assertThat(user.getPlan()).isEqualTo(Plan.FREE);
    assertThat(user.getBillingModePreference()).isEqualTo(BillingMode.AUTO);
    assertThat(user.getPlanStatus()).isNull();
    assertThat(user.getPlanValidUntil()).isNull();
    assertThat(user.getStripeCustomerId()).isNull();
  }
}
