package com.rft.chat_sribiddi.modelcatalog;

import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.billing.EntitlementService;
import com.rft.chat_sribiddi.billing.ManagedApiKeyProperties;
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
  private final ManagedApiKeyProperties managedApiKeyProperties;

  @GetMapping("/openai")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> openAiModels(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String apiKey = lookupUserKey(userId, Provider.OPENAI);
    return ResponseEntity.ok(modelCatalogService.listOpenAiModels(apiKey));
  }

  @GetMapping("/google")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> googleModels(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String apiKey = lookupUserKey(userId, Provider.GOOGLE);
    return ResponseEntity.ok(modelCatalogService.listGoogleModels(apiKey));
  }

  @GetMapping("/claude")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> claudeModels(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String apiKey = lookupUserKey(userId, Provider.CLAUDE);
    return ResponseEntity.ok(modelCatalogService.listClaudeModels(apiKey));
  }

  @GetMapping("/openrouter")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> openRouterModels(
      @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String userKey = lookupUserKey(userId, Provider.OPENROUTER);
    // Free-tier users typically have no BYOK key; fall back to the server-managed OpenRouter
    // key so the catalog is fetched live (and therefore actually contains `:free` model ids)
    // rather than the static YAML fallback, which would otherwise be empty after filtering.
    String apiKey = (userKey != null && !userKey.isBlank()) ? userKey : managedApiKeyOrNull();
    List<ModelCatalogProperties.ModelOption> models = modelCatalogService.listOpenRouterModels(apiKey);
    // Free-tier users can only pick OpenRouter `:free` models when chatting in managed mode;
    // hide the rest from the catalog so the UI never offers a model the plan will reject.
    return ResponseEntity.ok(entitlementService.filterOpenRouterModelsForPlan(userId, models));
  }

  private String lookupUserKey(String userId, Provider provider) {
    if (userId == null || userId.isBlank()) {
      return null;
    }
    return apiKeyService.getKey(userId, provider).orElse(null);
  }

  private String managedApiKeyOrNull() {
    String key = managedApiKeyProperties.getOpenrouter();
    return (key != null && !key.isBlank()) ? key : null;
  }
}
