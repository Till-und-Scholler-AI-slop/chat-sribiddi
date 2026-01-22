package com.rft.chat_sribiddi.authentification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

  @GetMapping("/")
  public String home() {
    return "Hello World";
  }

  @GetMapping("/secured")
  public String secured() {
    return "Secured";
  }
}
