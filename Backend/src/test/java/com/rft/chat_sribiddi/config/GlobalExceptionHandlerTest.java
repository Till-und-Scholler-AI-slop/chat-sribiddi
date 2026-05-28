package com.rft.chat_sribiddi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.auth.Plan;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void handlePlanRestriction_mapsMissingManagedProviderConfigTo503() {
    var ex =
        new PlanRestrictionException(
            PlanRestrictionException.Reason.MANAGED_PROVIDER_UNCONFIGURED,
            Plan.FREE,
            "Managed billing mode is not configured for provider OPENROUTER (server key missing)");

    var response = handler.handlePlanRestriction(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody())
        .containsEntry("reason", "MANAGED_PROVIDER_UNCONFIGURED")
        .containsEntry("plan", "FREE");
  }

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

  @Test
  void handleOpenAiService_preserves429Status() {
    var ex =
        com.openai.errors.RateLimitException.builder()
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("rate_limit_exceeded")
                    .message("Rate limit exceeded")
                    .param(java.util.Optional.empty())
                    .type("rate_limit_error")
                    .build())
            .build();

    var response = handler.handleOpenAiService(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getBody()).containsEntry("message", "Rate limit exceeded");
  }

  @Test
  void handleTransientAi_preservesWrappedProviderStatusAndMessage() {
    var providerCause =
        new WebClientResponseException(
            429,
            "Too Many Requests",
            null,
            "{\"message\":\"Provider rate limit\"}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    var ex =
        new org.springframework.ai.retry.TransientAiException(
            "Spring AI transient failure", providerCause);

    var response = handler.handleTransientAi(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getBody()).containsEntry("message", "Provider rate limit");
  }

  @Test
  void handleNonTransientAi_mapsToBadRequestWhenNoProviderStatusExists() {
    var ex = new org.springframework.ai.retry.NonTransientAiException("Invalid AI request");

    var response = handler.handleNonTransientAi(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("message", "Invalid AI request");
  }
}
