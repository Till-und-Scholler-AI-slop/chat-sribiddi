package com.rft.chat_sribiddi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

  private final AccountService accountService;

  @DeleteMapping
  public ResponseEntity<Void> deleteAccount(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    accountService.deleteAccount(principal.getSubject());

    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }

    return ResponseEntity.noContent().build();
  }
}
