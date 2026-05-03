package com.rft.chat_sribiddi.config;

import com.rft.chat_sribiddi.billing.PlanRestrictionException;
import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import com.rft.chat_sribiddi.chat.service.StreamErrorHandler;
import java.net.http.HttpTimeoutException;
import java.util.HashMap;
import java.util.Map;
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

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ChatNotFoundException.class)
  public ResponseEntity<Map<String, String>> handleChatNotFound(ChatNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
  }

  @ExceptionHandler(PlanRestrictionException.class)
  public ResponseEntity<Map<String, String>> handlePlanRestriction(PlanRestrictionException ex) {
    HttpStatus status =
        switch (ex.getReason()) {
          case MODEL_NOT_ALLOWED, DAILY_CAP_EXCEEDED -> HttpStatus.PAYMENT_REQUIRED;
          case TOOL_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
        };
    Map<String, String> body = new HashMap<>();
    body.put("message", ex.getMessage());
    body.put("reason", ex.getReason().name());
    if (ex.getPlan() != null) {
      body.put("plan", ex.getPlan().name());
    }
    return ResponseEntity.status(status).body(body);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("message",
            "A data integrity constraint was violated. The referenced resource may no longer exist."));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(Map.of("message", ex.getReason() != null ? ex.getReason() : ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, String> errors = new HashMap<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      errors.put(fieldError.getField(), fieldError.getDefaultMessage());
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
  }

  @ExceptionHandler(ResourceAccessException.class)
  public ResponseEntity<Map<String, String>> handleResourceAccess(ResourceAccessException ex) {
    if (isTimeout(ex)) {
      return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
          .body(
              Map.of(
                  "message",
                  "The AI provider timed out while processing the request. Try a shorter prompt or retry."));
    }

    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(Map.of("message", "Failed to reach the AI provider. Please retry."));
  }

  @ExceptionHandler(RestClientResponseException.class)
  public ResponseEntity<Map<String, String>> handleProviderResponse(RestClientResponseException ex) {
    HttpStatus mappedStatus = mapProviderStatus(ex.getStatusCode());
    String message = StreamErrorHandler.formatProviderError(
        ex.getResponseBodyAsString(), ex.getStatusCode().value());
    return ResponseEntity.status(mappedStatus).body(Map.of("message", message));
  }

  @ExceptionHandler(WebClientResponseException.class)
  public ResponseEntity<Map<String, String>> handleWebClientResponse(WebClientResponseException ex) {
    HttpStatus mappedStatus = mapProviderStatus(ex.getStatusCode());
    String message = StreamErrorHandler.formatProviderError(
        ex.getResponseBodyAsString(), ex.getStatusCode().value());
    return ResponseEntity.status(mappedStatus).body(Map.of("message", message));
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
