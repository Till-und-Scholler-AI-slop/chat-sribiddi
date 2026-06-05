package com.rft.chat_sribiddi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.access.AccessRestrictionException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void handleAccessRestriction_mapsMissingManagedProviderConfigTo503() {
    var ex =
        new AccessRestrictionException(
            AccessRestrictionException.Reason.MANAGED_PROVIDER_UNCONFIGURED,
            "OpenRouter Free is not configured on the server");

    var response = handler.handleAccessRestriction(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().code()).isEqualTo("MANAGED_PROVIDER_UNCONFIGURED");
    assertThat(response.getBody().details()).isEmpty();
  }

  @Test
  void handleAccessRestriction_mapsApiKeyRequiredToForbidden() {
    var ex =
        new AccessRestrictionException(
            AccessRestrictionException.Reason.API_KEY_REQUIRED, "Add an API key");

    var response = handler.handleAccessRestriction(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().code()).isEqualTo("API_KEY_REQUIRED");
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
    assertThat(response.getBody().message()).isEqualTo("Rate limit exceeded");
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
    assertThat(response.getBody().message()).isEqualTo("Invalid model");
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
    assertThat(response.getBody().message()).isEqualTo("Rate limit exceeded");
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
    assertThat(response.getBody().message()).isEqualTo("Provider rate limit");
  }

  @Test
  void handleNonTransientAi_mapsToBadRequestWhenNoProviderStatusExists() {
    var ex = new org.springframework.ai.retry.NonTransientAiException("Invalid AI request");

    var response = handler.handleNonTransientAi(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().message()).isEqualTo("Invalid AI request");
  }

  @Test
  void handleToolExecution_mapsSpringAiToolFailureToBadGateway() {
    var ex =
        new org.springframework.ai.tool.execution.ToolExecutionException(
            org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("url_fetcher")
                .description("Fetch a URL")
                .inputSchema("{}")
                .build(),
            new IllegalArgumentException("host was not approved"));

    var response = handler.handleToolExecution(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(response.getBody().message())
        .isEqualTo(
            "Tool 'url_fetcher' failed while processing the request: host was not approved");
  }
}
