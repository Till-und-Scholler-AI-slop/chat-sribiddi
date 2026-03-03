package com.rft.chat_sribiddi.modelcatalog;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/models")
@RequiredArgsConstructor
public class ModelController {

  private final ModelCatalogService modelCatalogService;

  @GetMapping("/openai")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> openAiModels() {
    return ResponseEntity.ok(modelCatalogService.listOpenAiModels());
  }

  @GetMapping("/google")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> googleModels() {
    return ResponseEntity.ok(modelCatalogService.listGoogleModels());
  }

  @GetMapping("/claude")
  public ResponseEntity<List<ModelCatalogProperties.ModelOption>> claudeModels() {
    return ResponseEntity.ok(modelCatalogService.listClaudeModels());
  }
}
