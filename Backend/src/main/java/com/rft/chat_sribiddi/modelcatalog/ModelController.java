package com.rft.chat_sribiddi.modelcatalog;

import com.rft.chat_sribiddi.access.CredentialAccessService;
import com.rft.chat_sribiddi.access.CredentialAccessService.ResolvedCredential;
import com.rft.chat_sribiddi.auth.RequestUserResolver;
import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import com.rft.chat_sribiddi.providers.ProviderCatalogService;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/models")
@RequiredArgsConstructor
public class ModelController {

  private final ModelCatalogService modelCatalogService;
  private final ApiKeyService apiKeyService;
  private final RequestUserResolver requestUserResolver;
  private final CredentialAccessService credentialAccessService;
  private final ProviderCatalogService providerCatalogService;

  @GetMapping
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> models(
      @AuthenticationPrincipal OidcUser principal,
      HttpServletRequest request,
      @RequestParam String provider,
      @RequestParam(defaultValue = "false") boolean expert) {
    Provider legacyProvider =
        providerCatalogService
            .legacyProvider(provider)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Provider is not executable yet"));
    return switch (legacyProvider) {
      case OPENAI -> openAiModels(principal, request, expert);
      case GOOGLE -> googleModels(principal, request, expert);
      case CLAUDE -> claudeModels(principal, request, expert);
      case OPENROUTER -> openRouterModels(principal, request, expert);
      case BRAVE_SEARCH -> ResponseEntity.ok(List.of());
    };
  }

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
    ResolvedCredential credential =
        credentialAccessService.readyUserCredential(userId, Provider.GOOGLE).orElse(null);
    List<ModelCatalogProperties.ModelOption> models =
        credential != null && credential.hasOAuthTokens() && !credential.hasApiKey()
            ? modelCatalogService.listGoogleModelsWithBearer(
                credential.accessToken(), credential.quotaProjectId())
            : modelCatalogService.listGoogleModels(credential == null ? null : credential.apiKey());
    return ResponseEntity.ok(
        usableModels(userId, Provider.GOOGLE, models, credential != null, expert));
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
    boolean hasUserKey = hasText(userKey);
    String apiKey =
        hasUserKey ? userKey : credentialAccessService.guestOpenRouterApiKey().orElse(null);
    List<ModelCatalogProperties.ModelOption> models = modelCatalogService.listOpenRouterModels(apiKey);
    return ResponseEntity.ok(
        usableModels(userId, Provider.OPENROUTER, models, hasUserKey, expert));
  }

  private List<ModelCatalogProperties.ModelOption> usableModels(
      String userId,
      Provider provider,
      List<ModelCatalogProperties.ModelOption> models,
      boolean hasUserKey,
      boolean expert) {
    List<ModelCatalogProperties.ModelOption> annotated =
        credentialAccessService.annotateModels(userId, provider, models, hasUserKey);
    return expert ? annotated : modelCatalogService.compactModels(provider, annotated);
  }

  private String lookupUserKey(String userId, Provider provider) {
    if (userId == null || userId.isBlank()) {
      return null;
    }
    return apiKeyService.getKey(userId, provider).orElse(null);
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
