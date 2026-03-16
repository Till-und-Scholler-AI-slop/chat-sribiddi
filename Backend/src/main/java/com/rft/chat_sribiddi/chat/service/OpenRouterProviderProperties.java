package com.rft.chat_sribiddi.chat.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.openrouter")
public class OpenRouterProviderProperties {

  private String baseUrl = "https://openrouter.ai/api/v1";
  private String completionsPath = "/chat/completions";
  private String modelCatalogUrl = "https://openrouter.ai/api/v1/models";

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getCompletionsPath() {
    return completionsPath;
  }

  public void setCompletionsPath(String completionsPath) {
    this.completionsPath = completionsPath;
  }

  public String getModelCatalogUrl() {
    return modelCatalogUrl;
  }

  public void setModelCatalogUrl(String modelCatalogUrl) {
    this.modelCatalogUrl = modelCatalogUrl;
  }
}
