package com.rft.chat_sribiddi.chat.service;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.openrouter")
public class OpenRouterProviderProperties {

  private String baseUrl = "https://openrouter.ai/api/v1";
  private String completionsPath = "/chat/completions";
  private String modelCatalogUrl = "https://openrouter.ai/api/v1/models";
  private Set<String> allowedModelCatalogHosts = Set.of("openrouter.ai");

  /**
   * OpenRouter request-attribution fields. OpenRouter requires/recommends callers to identify
   * themselves via the {@code HTTP-Referer} and {@code X-Title} headers so that requests are
   * correctly associated with the integrating app in their dashboard. Blank values disable the
   * corresponding header.
   */
  private String appName = "chat-sribiddi";

  private String referrer = "https://chat-sribiddi.local";

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

  public Set<String> getAllowedModelCatalogHosts() {
    return allowedModelCatalogHosts;
  }

  public void setAllowedModelCatalogHosts(Set<String> allowedModelCatalogHosts) {
    this.allowedModelCatalogHosts = allowedModelCatalogHosts;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getReferrer() {
    return referrer;
  }

  public void setReferrer(String referrer) {
    this.referrer = referrer;
  }
}
