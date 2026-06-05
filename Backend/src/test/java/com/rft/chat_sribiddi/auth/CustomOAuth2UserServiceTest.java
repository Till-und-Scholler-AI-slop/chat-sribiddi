package com.rft.chat_sribiddi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomOAuth2UserServiceTest {

  @Test
  void newUser_nullableFieldsAreNull() {
    User user = new User();
    assertThat(user.getEmail()).isNull();
    assertThat(user.getProviderId()).isNull();
    assertThat(user.getCustomInstructions()).isNull();
  }

  @Test
  void newUser_simulateOAuth2Creation_fieldsCorrectlyInitialized() {
    // Simulates the code path in CustomOAuth2UserService.loadUser()
    User user = new User();
    user.setEmail("test@example.com");
    user.setProviderId("google-sub-123");

    assertThat(user.getEmail()).isEqualTo("test@example.com");
    assertThat(user.getProviderId()).isEqualTo("google-sub-123");
    assertThat(user.getCustomInstructions()).isNull();
  }
}
