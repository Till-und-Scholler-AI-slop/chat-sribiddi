package com.rft.chat_sribiddi.modelcatalog;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/models")
@RequiredArgsConstructor
public class ModelController {

  private final ModelCatalogService modelCatalogService;
  private final ApiKeyService apiKeyService;

  @GetMapping("/openai")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> openAiModels(
      @AuthenticationPrincipal OidcUser principal) {
    String apiKey = resolveApiKey(principal, Provider.OPENAI);
    return ResponseEntity.ok(modelCatalogService.listOpenAiModels(apiKey));
  }

  @GetMapping("/google")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> googleModels(
      @AuthenticationPrincipal OidcUser principal) {
    String apiKey = resolveApiKey(principal, Provider.GOOGLE);
    return ResponseEntity.ok(modelCatalogService.listGoogleModels(apiKey));
  }

  @GetMapping("/claude")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> claudeModels(
      @AuthenticationPrincipal OidcUser principal) {
    String apiKey = resolveApiKey(principal, Provider.CLAUDE);
    return ResponseEntity.ok(modelCatalogService.listClaudeModels(apiKey));
  }

  @GetMapping("/openrouter")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> openRouterModels(
      @AuthenticationPrincipal OidcUser principal) {
    String apiKey = resolveApiKey(principal, Provider.OPENROUTER);
    return ResponseEntity.ok(modelCatalogService.listOpenRouterModels(apiKey));
  }

  private String resolveApiKey(OidcUser principal, Provider provider) {
    if (principal == null) {
      return null;
    }
    return apiKeyService.getKey(principal.getSubject(), provider).orElse(null);
  }
}
