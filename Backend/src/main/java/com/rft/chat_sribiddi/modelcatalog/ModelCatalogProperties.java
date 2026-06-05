package com.rft.chat_sribiddi.modelcatalog;

import java.io.Serial;
import java.io.Serializable;
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

  private FeaturedModels featured = new FeaturedModels();

  @Getter
  @Setter
  public static class FeaturedModels {
    private List<String> openai = new ArrayList<>();
    private List<String> google = new ArrayList<>();
    private List<String> claude = new ArrayList<>();
    private List<String> openrouter = new ArrayList<>();
  }

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ModelOption implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

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

    /** Credential-aware availability flag populated before the option is returned to clients. */
    private AvailabilityFlag availability;

    public ModelOption(String id, String label) {
      this.id = id;
      this.label = label;
    }

    /** Shallow copy used by credential annotation to avoid mutating cached entries. */
    public ModelOption copy() {
      ModelOption c = new ModelOption();
      c.id = this.id;
      c.label = this.label;
      c.canonicalSlug = this.canonicalSlug;
      c.created = this.created;
      c.description = this.description;
      c.contextLength = this.contextLength;
      c.maxCompletionTokens = this.maxCompletionTokens;
      c.promptPrice = this.promptPrice;
      c.completionPrice = this.completionPrice;
      c.requestPrice = this.requestPrice;
      c.imagePrice = this.imagePrice;
      c.modality = this.modality;
      c.inputModalities = this.inputModalities;
      c.outputModalities = this.outputModalities;
      c.supportedParameters = this.supportedParameters;
      c.availability = this.availability;
      return c;
    }
  }
}
