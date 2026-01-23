package com.rft.chat_sribiddi.modelcatalog;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/models")
@RequiredArgsConstructor
public class ModelController {

  private final ModelCatalogService modelCatalogService;

  @GetMapping("/openai")
  public List<ModelCatalogProperties.ModelOption> openAiModels() {
    return modelCatalogService.listOpenAiModels();
  }

  @GetMapping("/google")
  public List<ModelCatalogProperties.ModelOption> googleModels() {
    return modelCatalogService.listGoogleModels();
  }
}
