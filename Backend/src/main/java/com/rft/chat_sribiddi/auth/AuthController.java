package com.rft.chat_sribiddi.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

  @GetMapping("/secured")
  public ResponseEntity<Void> authenticated() {
    return ResponseEntity.noContent().build();
  }
}
