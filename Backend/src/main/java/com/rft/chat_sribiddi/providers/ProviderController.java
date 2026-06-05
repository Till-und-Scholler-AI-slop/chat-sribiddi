package com.rft.chat_sribiddi.providers;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionService.ProviderCatalogItem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/providers")
@RequiredArgsConstructor
public class ProviderController {

  private final ProviderConnectionService providerConnectionService;
  private final RequestUserResolver requestUserResolver;

  @GetMapping
  public ResponseEntity<List<ProviderCatalogItem>> providers(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    return ResponseEntity.ok(providerConnectionService.listCatalogWithStatus(userId));
  }
}
