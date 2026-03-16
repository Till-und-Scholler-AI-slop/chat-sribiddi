package com.rft.chat_sribiddi.config;

import com.rft.chat_sribiddi.chat.exception.ChatNotFoundException;
import java.net.http.HttpTimeoutException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
    HttpStatus mappedStatus =
        ex.getStatusCode().is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
    String responseBody = ex.getResponseBodyAsString();
    String message =
        (responseBody == null || responseBody.isBlank())
            ? "AI provider rejected the request. Check your model and API key."
            : responseBody;
    return ResponseEntity.status(mappedStatus).body(Map.of("message", message));
  }

  @ExceptionHandler(WebClientResponseException.class)
  public ResponseEntity<Map<String, String>> handleWebClientResponse(WebClientResponseException ex) {
    HttpStatus mappedStatus =
        ex.getStatusCode().is4xxClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
    String responseBody = ex.getResponseBodyAsString();
    String message =
        (responseBody == null || responseBody.isBlank())
            ? "AI provider rejected the request. Check your model and API key."
            : responseBody;
    return ResponseEntity.status(mappedStatus).body(Map.of("message", message));
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
