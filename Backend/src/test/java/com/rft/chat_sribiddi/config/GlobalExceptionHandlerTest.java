package com.rft.chat_sribiddi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void handleWebClientResponse_preserves429Status() {
    var ex =
        new WebClientResponseException(
            429,
            "Too Many Requests",
            null,
            "{\"message\":\"Rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

    var response = handler.handleWebClientResponse(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getBody()).containsEntry("message", "Rate limit exceeded");
  }

  @Test
  void handleProviderResponse_keepsOther4xxMappedToBadRequest() {
    var ex =
        new RestClientResponseException(
            "Bad Request",
            400,
            "Bad Request",
            null,
            "{\"message\":\"Invalid model\"}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

    var response = handler.handleProviderResponse(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("message", "Invalid model");
  }
}
