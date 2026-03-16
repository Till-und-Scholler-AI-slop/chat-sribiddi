package com.rft.chat_sribiddi.modelcatalog;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.models")
public class ModelCatalogProperties {

  private List<ModelOption> openai = new ArrayList<>();

  private List<ModelOption> google = new ArrayList<>();

  private List<ModelOption> claude = new ArrayList<>();

  private List<ModelOption> openrouter = new ArrayList<>();

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ModelOption {
    private String id;
    private String label;
    private String description;
    private Long contextWindow;
    private Pricing pricing;

    public ModelOption(String id, String label) {
      this(id, label, null, null, null);
    }
  }

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Pricing {
    private String prompt;
    private String completion;
    private String image;
    private String request;
  }
}
