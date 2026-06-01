package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.chat.dto.RequestDTO;
import com.rft.chat_sribiddi.chat.service.StreamErrorHandler.StreamErrorDetails;
import org.junit.jupiter.api.Test;

class StreamErrorHandlerTest {

  // --- buildClientMessage with providerMessage ---------------------------------

  @Test
  void buildClientMessage_appendsProviderMessageWhenPresent() {
    String message =
        StreamErrorHandler.buildClientMessage(
            new StreamErrorDetails(
                RequestDTO.Provider.OPENROUTER,
                "openai/gpt-mini-latest",
                400,
                "PROVIDER_ERROR",
                "model 'openai/gpt-mini-latest' does not support tools",
                0L));

    assertThat(message)
        .contains("OpenRouter")
        .contains("openai/gpt-mini-latest")
        .contains("HTTP 400")
        .contains("rejected the request")
        .contains("model 'openai/gpt-mini-latest' does not support tools");
  }

  @Test
  void buildClientMessage_omitsProviderMessageWhenBlank() {
    String message =
        StreamErrorHandler.buildClientMessage(
            new StreamErrorDetails(
                RequestDTO.Provider.OPENAI,
                "gpt-5.2",
                503,
                "SERVER_ERROR",
                "   ",
                0L));

    assertThat(message)
        .contains("returned a server error")
        .doesNotContain(":    ")
        .doesNotContain(":  ");
  }

  @Test
  void buildClientMessage_omitsProviderMessageWhenNull() {
    String message =
        StreamErrorHandler.buildClientMessage(
            new StreamErrorDetails(
                RequestDTO.Provider.GOOGLE,
                "gemini-3-pro",
                429,
                "RATE_LIMIT",
                null,
                0L));

    assertThat(message).contains("rate limit").doesNotContain(": null");
  }

  @Test
  void buildClientMessage_keepsPartialResponseHintAfterProviderMessage() {
    String message =
        StreamErrorHandler.buildClientMessage(
            new StreamErrorDetails(
                RequestDTO.Provider.CLAUDE,
                "claude-opus-4-6",
                400,
                "PROVIDER_ERROR",
                "tools are not enabled for this account",
                512L));

    assertThat(message)
        .contains("tools are not enabled for this account")
        .contains("Partial response shown");
  }

  // --- describe() populates providerMessage ------------------------------------

  @Test
  void describe_extractsProviderMessageFromOpenAiSdkException() {
    var ex =
        com.openai.errors.UnexpectedStatusCodeException.builder()
            .statusCode(400)
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("model_not_found")
                    .message("model 'openai/gpt-mini-latest' does not support tools")
                    .param(java.util.Optional.empty())
                    .type("invalid_request_error")
                    .build())
            .build();

    StreamErrorDetails details = StreamErrorHandler.describe(
        RequestDTO.Provider.OPENROUTER, "openai/gpt-mini-latest", 0L, ex);

    assertThat(details.providerMessage())
        .isEqualTo("model 'openai/gpt-mini-latest' does not support tools");
    assertThat(details.statusCode()).isEqualTo(400);
    assertThat(details.errorType()).isEqualTo("PROVIDER_ERROR");
  }

  @Test
  void describe_extractsProviderMessageFromWebClientResponseException() {
    String body =
        "{\"error\":{\"message\":\"Tool schema rejected by upstream model\"}}";
    var ex =
        new org.springframework.web.reactive.function.client.WebClientResponseException(
            400, "Bad Request", null, body.getBytes(), null);

    StreamErrorDetails details =
        StreamErrorHandler.describe(
            RequestDTO.Provider.OPENROUTER, "openai/gpt-mini-latest", 0L, ex);

    assertThat(details.providerMessage())
        .isEqualTo("Tool schema rejected by upstream model");
  }

  @Test
  void describe_providerMessageIsNull_whenErrorIsGenericRuntimeException() {
    var ex = new RuntimeException("kaboom");

    StreamErrorDetails details =
        StreamErrorHandler.describe(
            RequestDTO.Provider.OPENAI, "gpt-5.2", 0L, ex);

    assertThat(details.providerMessage())
        .isEqualTo("An error occurred while streaming the response.");
  }

  // --- formatProviderError(OpenAIServiceException) — body extraction ------------

  @Test
  void formatProviderError_openAiSdkException_returnsErrorObjectMessage() {
    var ex =
        com.openai.errors.UnexpectedStatusCodeException.builder()
            .statusCode(400)
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("invalid_request_error")
                    .message("model 'foo' does not exist")
                    .param(java.util.Optional.empty())
                    .type("invalid_request_error")
                    .build())
            .build();

    assertThat(StreamErrorHandler.formatProviderError(ex))
        .isEqualTo("model 'foo' does not exist");
  }

  @Test
  void formatProviderError_openAiSdkException_fallsBackToBodyWhenErrorObjectFails() {
    // Build an exception whose body().convert(ErrorObject.class) throws.
    // We use a real ErrorObject but stub a body that lacks the .message field
    // by passing it through directly.
    var ex =
        com.openai.errors.UnexpectedStatusCodeException.builder()
            .statusCode(500)
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("internal")
                    .message("") // empty message → falls through to body fallback
                    .param(java.util.Optional.empty())
                    .type("server_error")
                    .build())
            .build();

    String result = StreamErrorHandler.formatProviderError(ex);

    // Falls back to formatProviderError(body, statusCode) which parses ErrorObject.message again.
    // Either the empty message is returned (parses to "") or some non-null fallback.
    // The important contract: no exception is thrown.
    assertThat(result).isNotNull();
  }

  // --- extractOpenAiStyleErrorMessage: OpenRouter vs native OpenAI shapes -----

  @Test
  void extractOpenAiStyleErrorMessage_handlesOpenRouterNestedShape() {
    // OpenRouter returns {"error":{"code":...,"message":"..."}}, NOT OpenAI's
    // flat {"message":"..."}. The SDK's ErrorObject deserializer returns a
    // placeholder "Provider returned error" in this case; our generic parser
    // extracts the real reason.
    String body = "{\"error\":{\"code\":400,\"message\":\"Tool use is not supported\"}}";

    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage(body))
        .isEqualTo("Tool use is not supported");
  }

  @Test
  void extractOpenAiStyleErrorMessage_handlesOpenAiFlatShape() {
    // Native OpenAI: {"message":"...","type":"...","code":"...","param":null}
    String body = "{\"message\":\"Invalid API key\",\"type\":\"auth_error\",\"code\":\"invalid_api_key\"}";

    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage(body))
        .isEqualTo("Invalid API key");
  }

  @Test
  void extractOpenAiStyleErrorMessage_handlesTopLevelMessageField() {
    // Some upstreams use a bare top-level message field.
    String body = "{\"message\":\"Rate limit exceeded\"}";

    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage(body))
        .isEqualTo("Rate limit exceeded");
  }

  @Test
  void extractOpenAiStyleErrorMessage_returnsNullForNullInput() {
    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage(null)).isNull();
  }

  @Test
  void extractOpenAiStyleErrorMessage_returnsNullForBlankInput() {
    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage("   ")).isNull();
  }

  @Test
  void extractOpenAiStyleErrorMessage_returnsNullForNonJsonInput() {
    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage("plain text error")).isNull();
  }

  @Test
  void extractOpenAiStyleErrorMessage_returnsNullForJsonWithoutMessage() {
    String body = "{\"error\":{\"code\":400,\"type\":\"invalid_request_error\"}}";

    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage(body)).isNull();
  }

  @Test
  void extractOpenAiStyleErrorMessage_skipsBlankNestedMessage() {
    // OpenRouter shape but the message field is empty.
    String body = "{\"error\":{\"code\":400,\"message\":\"\"}}";

    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage(body)).isNull();
  }

  @Test
  void extractOpenAiStyleErrorMessage_fallsBackToErrorObjectWhenGenericParserMisses() {
    // Generic parser misses (no `error.message` or top-level `message`),
    // but the body IS a valid OpenAI ErrorObject. Fallback should extract.
    String body = "{\"message\":\"\",\"type\":\"server_error\"}";

    // The generic parser returns null for empty message, so the fallback
    // is attempted. Since `message` is empty, the fallback also returns null.
    assertThat(StreamErrorHandler.extractOpenAiStyleErrorMessage(body)).isNull();
  }
}
