package com.rft.chat_sribiddi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AuthControllerTest {

  private final AuthController controller = new AuthController();

  @Test
  void authenticated_returnsNoContent() {
    var response = controller.authenticated();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getBody()).isNull();
  }
}
