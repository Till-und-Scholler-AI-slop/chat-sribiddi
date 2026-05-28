package com.rft.chat_sribiddi.chat.service;

import com.anthropic.errors.AnthropicServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ErrorObject;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
public final class StreamErrorHandler {

  private static final String EVENT_ERROR = "error";
  private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

  private StreamErrorHandler() {}

  public static StreamErrorDetails describe(
      Provider provider, String modelId, long streamedTextUtf8Bytes, Throwable error) {
    Integer statusCode = extractStatusCode(error);
    return new StreamErrorDetails(
        provider,
        modelId,
        statusCode,
        classify(error, statusCode),
        streamedTextUtf8Bytes);
  }

  public static String extractProviderMessage(Throwable error) {
    String springAiMessage = null;
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      switch (current) {
        case WebClientResponseException webEx -> {
          return formatProviderError(
              webEx.getResponseBodyAsString(), webEx.getStatusCode().value());
        }
        case RestClientResponseException restEx -> {
          return formatProviderError(
              restEx.getResponseBodyAsString(), restEx.getStatusCode().value());
        }
        case OpenAIServiceException openAiEx -> {
          return formatProviderError(openAiEx);
        }
        case AnthropicServiceException anthropicEx -> {
          return formatProviderError(anthropicEx);
        }
        case TransientAiException aiEx -> {
          if (springAiMessage == null
              && aiEx.getMessage() != null
              && !aiEx.getMessage().isBlank()) {
            springAiMessage = aiEx.getMessage();
          }
        }
        case NonTransientAiException aiEx -> {
          if (springAiMessage == null
              && aiEx.getMessage() != null
              && !aiEx.getMessage().isBlank()) {
            springAiMessage = aiEx.getMessage();
          }
        }
        default -> {
          // Continue walking through Spring AI wrapper exceptions to the provider root cause.
        }
      }
      current = current.getCause();
      depth++;
    }
    if (springAiMessage != null) {
      return springAiMessage;
    }
    return "An error occurred while streaming the response.";
  }

  public static String formatProviderError(OpenAIServiceException ex) {
    try {
      String message = ex.body().convert(ErrorObject.class).message();
      if (message != null && !message.isBlank()) {
        return message;
      }
    } catch (Exception e) {
      log.debug("Failed to extract OpenAI SDK error body", e);
    }
    return formatProviderError(ex.body().toString(), ex.statusCode());
  }

  public static String formatProviderError(AnthropicServiceException ex) {
    return formatProviderError(ex.body().toString(), ex.statusCode());
  }

  public static String formatProviderError(String responseBody, int statusCode) {
    if (responseBody != null && !responseBody.isBlank()) {
      String parsed = parseErrorMessage(responseBody);
      if (parsed != null) {
        return parsed;
      }
      return responseBody;
    }
    if (statusCode == 429) {
      return "AI provider rate limit exceeded (HTTP 429). Try again later, switch models/providers, or add credits if required.";
    }
    return "AI provider rejected the request (HTTP %d). Check your model and API key."
        .formatted(statusCode);
  }

  public static String classify(Throwable error, Integer statusCode) {
    if (statusCode != null) {
      if (statusCode == 429) {
        return "RATE_LIMIT";
      }
      if (statusCode >= 500) {
        return "SERVER_ERROR";
      }
      if (statusCode >= 400) {
        return "PROVIDER_ERROR";
      }
    }

    if (isTimeoutError(error)) {
      return "TIMEOUT";
    }
    if (isParseError(error)) {
      return "PARSE_ERROR";
    }
    if (hasCause(error, TransientAiException.class)) {
      return "SERVER_ERROR";
    }
    if (hasCause(error, NonTransientAiException.class)) {
      return "PROVIDER_ERROR";
    }
    return "UNKNOWN";
  }

  public static boolean isExpectedFailure(String errorType) {
    return switch (errorType) {
      case "TIMEOUT", "PARSE_ERROR", "RATE_LIMIT", "SERVER_ERROR", "PROVIDER_ERROR" -> true;
      default -> false;
    };
  }

  public static String buildClientMessage(StreamErrorDetails details) {
    String providerName = providerDisplayName(details.provider());
    StringBuilder builder =
        new StringBuilder("Response from ")
            .append(providerName)
            .append(" (")
            .append(details.modelId())
            .append(") was interrupted");

    if (details.statusCode() != null) {
      builder.append(" (HTTP ").append(details.statusCode()).append(")");
    }

    builder.append(". ");
    builder.append(
        switch (details.errorType()) {
          case "TIMEOUT" -> "The provider timed out before completion.";
          case "PARSE_ERROR" -> "The streamed response could not be parsed completely.";
          case "RATE_LIMIT" -> "The provider rate limit was reached.";
          case "SERVER_ERROR" -> "The provider returned a server error.";
          case "PROVIDER_ERROR" -> "The provider rejected the request.";
          default -> "An unexpected streaming error occurred.";
        });
    if (details.receivedBytes() > 0) {
      builder.append(" Partial response shown.");
    }
    return builder.toString();
  }

  private static Integer extractStatusCode(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      switch (current) {
        case WebClientResponseException webEx -> {
          return webEx.getStatusCode().value();
        }
        case RestClientResponseException restEx -> {
          return restEx.getStatusCode().value();
        }
        case OpenAIServiceException openAiEx -> {
          return openAiEx.statusCode();
        }
        case AnthropicServiceException anthropicEx -> {
          return anthropicEx.statusCode();
        }
        default -> {
          current = current.getCause();
          depth++;
        }
      }
    }
    return null;
  }

  private static boolean isTimeoutError(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      if (current instanceof java.util.concurrent.TimeoutException
          || current instanceof java.net.SocketTimeoutException
          || current instanceof java.net.http.HttpTimeoutException) {
        return true;
      }
      String message = current.getMessage();
      if (message != null && message.toLowerCase(Locale.ROOT).contains("timeout")) {
        return true;
      }
      current = current.getCause();
      depth++;
    }
    return false;
  }

  private static boolean isParseError(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      String className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
      if (className.contains("parse")
          || className.contains("decode")
          || className.contains("deserial")) {
        return true;
      }
      String message = current.getMessage();
      if (message != null) {
        String lowered = message.toLowerCase(Locale.ROOT);
        if (lowered.contains("parse") || lowered.contains("json")) {
          return true;
        }
      }
      current = current.getCause();
      depth++;
    }
    return false;
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
      depth++;
    }
    return false;
  }

  private static String providerDisplayName(Provider provider) {
    if (provider == null) {
      return "AI provider";
    }
    return switch (provider) {
      case OPENAI -> "OpenAI";
      case GOOGLE -> "Google";
      case CLAUDE -> "Claude";
      case OPENROUTER -> "OpenRouter";
      case BRAVE_SEARCH -> "Brave Search";
    };
  }

  static String parseErrorMessage(String responseBody) {
    try {
      JsonNode root = ERROR_MAPPER.readTree(responseBody);
      JsonNode errorNode = root.path(EVENT_ERROR);
      if (!errorNode.isMissingNode()) {
        JsonNode messageNode = errorNode.path("message");
        if (messageNode.isTextual() && !messageNode.asText().isBlank()) {
          return messageNode.asText();
        }
      }
      JsonNode messageNode = root.path("message");
      if (messageNode.isTextual() && !messageNode.asText().isBlank()) {
        return messageNode.asText();
      }
    } catch (Exception e) {
      log.debug("Failed to parse provider error response as JSON", e);
    }
    return null;
  }

  public record StreamErrorDetails(
      Provider provider,
      String modelId,
      Integer statusCode,
      String errorType,
      Long receivedBytes) {}
}
