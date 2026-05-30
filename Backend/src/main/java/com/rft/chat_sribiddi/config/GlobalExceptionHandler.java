package com.rft.chat_sribiddi.config;

import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIServiceException;
import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.service.StreamErrorHandler;
import java.net.http.HttpTimeoutException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ChatNotFoundException.class)
  public ResponseEntity<ApiError> handleChatNotFound(ChatNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
  }

  @ExceptionHandler(PlanRestrictionException.class)
  public ResponseEntity<ApiError> handlePlanRestriction(PlanRestrictionException ex) {
    HttpStatus status =
        switch (ex.getReason()) {
          case MODEL_NOT_ALLOWED, DAILY_CAP_EXCEEDED -> HttpStatus.PAYMENT_REQUIRED;
          case PARALLEL_STREAMS_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
          case TOOL_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
          case MANAGED_PROVIDER_UNCONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    Map<String, String> details = new HashMap<>();
    if (ex.getPlan() != null) {
      details.put("plan", ex.getPlan().name());
    }
    return ResponseEntity.status(status).body(new ApiError(ex.getMessage(), ex.getReason().name(), details));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
    log.warn("Data integrity violation encountered", ex);
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiError("A data integrity constraint was violated. The referenced resource may no longer exist."));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(new ApiError(ex.getReason() != null ? ex.getReason() : ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, String> errors = new HashMap<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      errors.put(fieldError.getField(), fieldError.getDefaultMessage());
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("Validation failed", "VALIDATION_ERROR", errors));
  }

  @ExceptionHandler(ResourceAccessException.class)
  public ResponseEntity<ApiError> handleResourceAccess(ResourceAccessException ex) {
    if (isTimeout(ex)) {
      return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
          .body(new ApiError("The AI provider timed out while processing the request. Try a shorter prompt or retry."));
    }

    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ApiError("Failed to reach the AI provider. Please retry."));
  }

  @ExceptionHandler(RestClientResponseException.class)
  public ResponseEntity<ApiError> handleProviderResponse(RestClientResponseException ex) {
    HttpStatus mappedStatus = mapProviderStatus(ex.getStatusCode());
    String message = StreamErrorHandler.formatProviderError(
        ex.getResponseBodyAsString(), ex.getStatusCode().value());
    return ResponseEntity.status(mappedStatus).body(new ApiError(message));
  }

  @ExceptionHandler(WebClientResponseException.class)
  public ResponseEntity<ApiError> handleWebClientResponse(WebClientResponseException ex) {
    HttpStatus mappedStatus = mapProviderStatus(ex.getStatusCode());
    String message = StreamErrorHandler.formatProviderError(
        ex.getResponseBodyAsString(), ex.getStatusCode().value());
    return ResponseEntity.status(mappedStatus).body(new ApiError(message));
  }

  @ExceptionHandler(OpenAIServiceException.class)
  public ResponseEntity<ApiError> handleOpenAiService(OpenAIServiceException ex) {
    HttpStatus mappedStatus = mapProviderStatus(HttpStatusCode.valueOf(ex.statusCode()));
    String message = StreamErrorHandler.formatProviderError(ex);
    return ResponseEntity.status(mappedStatus).body(new ApiError(message));
  }

  @ExceptionHandler(AnthropicServiceException.class)
  public ResponseEntity<ApiError> handleAnthropicService(AnthropicServiceException ex) {
    HttpStatus mappedStatus = mapProviderStatus(HttpStatusCode.valueOf(ex.statusCode()));
    String message = StreamErrorHandler.formatProviderError(ex);
    return ResponseEntity.status(mappedStatus).body(new ApiError(message));
  }

  @ExceptionHandler(TransientAiException.class)
  public ResponseEntity<ApiError> handleTransientAi(TransientAiException ex) {
    return handleSpringAiProviderException(ex, HttpStatus.BAD_GATEWAY);
  }

  @ExceptionHandler(NonTransientAiException.class)
  public ResponseEntity<ApiError> handleNonTransientAi(NonTransientAiException ex) {
    return handleSpringAiProviderException(ex, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(ToolExecutionException.class)
  public ResponseEntity<ApiError> handleToolExecution(ToolExecutionException ex) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ApiError(StreamErrorHandler.extractProviderMessage(ex)));
  }

  /**
   * Preserves provider HTTP 429 so the frontend can distinguish rate limits,
   * while keeping other provider-side 4xx responses normalized to HTTP 400.
   */
  private HttpStatus mapProviderStatus(HttpStatusCode statusCode) {
    if (statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
      return HttpStatus.TOO_MANY_REQUESTS;
    }
    return statusCode.is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
  }

  private ResponseEntity<ApiError> handleSpringAiProviderException(
      Throwable ex, HttpStatus fallbackStatus) {
    StreamErrorHandler.StreamErrorDetails details =
        StreamErrorHandler.describe(null, null, 0, ex);
    HttpStatus status =
        details.statusCode() == null
            ? fallbackStatus
            : mapProviderStatus(HttpStatusCode.valueOf(details.statusCode()));
    return ResponseEntity.status(status)
        .body(new ApiError(StreamErrorHandler.extractProviderMessage(ex)));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
    // On SSE/async streams, client disconnects can cause secondary write failures (e.g. Map with text/event-stream).
    // Log at debug to avoid noise; the primary stream error was already handled in ChatStreamSubscriber.
    log.debug("Unexpected error (possibly async/SSE disconnect): {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("An unexpected error occurred. Please try again later."));
  }

  private boolean isTimeout(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof HttpTimeoutException) {
        return true;
      }
      if ("io.netty.handler.timeout.ReadTimeoutException".equals(current.getClass().getName())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
