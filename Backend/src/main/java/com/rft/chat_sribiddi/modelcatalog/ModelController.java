package com.rft.chat_sribiddi.modelcatalog;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
      @RequestHeader(value = "X-User-Id", required = false) String userId) {
    String apiKey = resolveApiKey(userId, Provider.OPENAI);
    return ResponseEntity.ok(modelCatalogService.listOpenAiModels(apiKey));
  }

  @GetMapping("/google")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> googleModels(
      @RequestHeader(value = "X-User-Id", required = false) String userId) {
    String apiKey = resolveApiKey(userId, Provider.GOOGLE);
    return ResponseEntity.ok(modelCatalogService.listGoogleModels(apiKey));
  }

  @GetMapping("/claude")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> claudeModels(
      @RequestHeader(value = "X-User-Id", required = false) String userId) {
    String apiKey = resolveApiKey(userId, Provider.CLAUDE);
    return ResponseEntity.ok(modelCatalogService.listClaudeModels(apiKey));
  }

  private String resolveApiKey(String userId, Provider provider) {
    if (userId == null || userId.isBlank()) {
      return null;
    }
    return apiKeyService.getKey(userId, provider).orElse(null);
  }
}
