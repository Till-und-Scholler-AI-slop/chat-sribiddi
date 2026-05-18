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
import org.springframework.web.bind.annotation.RequestParam;
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
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RequestParam(defaultValue = "false") boolean expert) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String apiKey = lookupUserKey(userId, Provider.OPENAI);
    List<ModelCatalogProperties.ModelOption> models = modelCatalogService.listOpenAiModels(apiKey);
    return ResponseEntity.ok(usableModels(userId, Provider.OPENAI, models, hasText(apiKey), expert));
  }

  @GetMapping("/google")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> googleModels(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RequestParam(defaultValue = "false") boolean expert) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String apiKey = lookupUserKey(userId, Provider.GOOGLE);
    List<ModelCatalogProperties.ModelOption> models = modelCatalogService.listGoogleModels(apiKey);
    return ResponseEntity.ok(usableModels(userId, Provider.GOOGLE, models, hasText(apiKey), expert));
  }

  @GetMapping("/claude")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> claudeModels(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RequestParam(defaultValue = "false") boolean expert) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String apiKey = lookupUserKey(userId, Provider.CLAUDE);
    List<ModelCatalogProperties.ModelOption> models = modelCatalogService.listClaudeModels(apiKey);
    return ResponseEntity.ok(usableModels(userId, Provider.CLAUDE, models, hasText(apiKey), expert));
  }

  @GetMapping("/openrouter")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> openRouterModels(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RequestParam(defaultValue = "false") boolean expert) {
    String userId = requestUserResolver.resolveUserId(principal, request).orElse(null);
    String userKey = lookupUserKey(userId, Provider.OPENROUTER);
    // Free-tier users typically have no BYOK key; fall back to the server-managed OpenRouter
    // key so the catalog is fetched live (and therefore actually contains `:free` model ids)
    // rather than the static YAML fallback, which would otherwise be empty.
    boolean hasUserKey = hasText(userKey);
    String apiKey = hasUserKey ? userKey : managedApiKeyOrNull();
    List<ModelCatalogProperties.ModelOption> models = modelCatalogService.listOpenRouterModels(apiKey);
    // Without a user key, only plan-usable OpenRouter models are returned. With a user key, BYOK
    // takes precedence and all returned models are selectable.
    return ResponseEntity.ok(
        usableModels(userId, Provider.OPENROUTER, models, hasUserKey, expert));
  }

  private List<ModelCatalogProperties.ModelOption> usableModels(
      String userId,
      Provider provider,
      List<ModelCatalogProperties.ModelOption> models,
      boolean hasUserKey,
      boolean expert) {
    List<ModelCatalogProperties.ModelOption> usable =
        entitlementService.filterUsableModelsForPlan(userId, provider, models, hasUserKey);
    return expert ? usable : modelCatalogService.compactModels(provider, usable);
  }

  private String lookupUserKey(String userId, Provider provider) {
    if (userId == null || userId.isBlank()) {
      return null;
    }
    return apiKeyService.getKey(userId, provider).orElse(null);
  }

  private String managedApiKeyOrNull() {
    String key = managedApiKeyProperties.getOpenrouter();
    return hasText(key) ? key : null;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
