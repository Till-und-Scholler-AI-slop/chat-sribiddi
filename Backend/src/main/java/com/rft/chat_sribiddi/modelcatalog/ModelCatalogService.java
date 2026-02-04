package com.rft.chat_sribiddi.modelcatalog;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModelCatalogService {

  private final ModelCatalogProperties properties;

  public List<ModelCatalogProperties.ModelOption> listOpenAiModels() {
    return List.copyOf(properties.getOpenai());
  }

  public List<ModelCatalogProperties.ModelOption> listGoogleModels() {
    return List.copyOf(properties.getGoogle());
  }
}
