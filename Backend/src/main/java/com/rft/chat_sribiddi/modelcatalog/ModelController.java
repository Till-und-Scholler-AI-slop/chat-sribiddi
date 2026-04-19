package com.rft.chat_sribiddi.modelcatalog;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
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
@RequestMapping("/models")
@RequiredArgsConstructor
public class ModelController {

  private final ModelCatalogService modelCatalogService;
  private final ApiKeyService apiKeyService;
  private final RequestUserResolver requestUserResolver;
  private final EntitlementService entitlementService;

  @GetMapping("/openai")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> openAiModels(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String apiKey = resolveApiKey(principal, request, Provider.OPENAI);
    return ResponseEntity.ok(modelCatalogService.listOpenAiModels(apiKey));
  }

  @GetMapping("/google")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> googleModels(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String apiKey = resolveApiKey(principal, request, Provider.GOOGLE);
    return ResponseEntity.ok(modelCatalogService.listGoogleModels(apiKey));
  }

  @GetMapping("/claude")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> claudeModels(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String apiKey = resolveApiKey(principal, request, Provider.CLAUDE);
    return ResponseEntity.ok(modelCatalogService.listClaudeModels(apiKey));
  }

  @GetMapping("/openrouter")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> openRouterModels(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String apiKey = resolveApiKey(principal, request, Provider.OPENROUTER);
    List<ModelCatalogProperties.ModelOption> models = modelCatalogService.listOpenRouterModels(apiKey);
    // Free-tier users can only pick OpenRouter `:free` models when chatting in managed mode;
    // hide the rest from the catalog so the UI never offers a model the plan will reject.
    return ResponseEntity.ok(entitlementService.filterOpenRouterModelsForPlan(userId, models));
  }

  private String resolveApiKey(OidcUser principal, HttpServletRequest request, Provider provider) {
    return requestUserResolver
        .resolveUserId(principal, request)
        .flatMap(userId -> apiKeyService.getKey(userId, provider))
        .orElse(null);
  }
}
