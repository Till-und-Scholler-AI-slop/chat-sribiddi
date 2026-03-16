package com.rft.chat_sribiddi.modelcatalog;

import java.util.ArrayList;
import java.util.List;
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
  public static class ModelOption {
    private String id;
    private String label;
    private String canonicalSlug;
    private Long created;
    private String description;
    private Integer contextLength;
    private Integer maxCompletionTokens;
    private String promptPrice;
    private String completionPrice;
    private String requestPrice;
    private String imagePrice;
    private String modality;
    private List<String> inputModalities = new ArrayList<>();
    private List<String> outputModalities = new ArrayList<>();
    private List<String> supportedParameters = new ArrayList<>();

    public ModelOption(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }
}
