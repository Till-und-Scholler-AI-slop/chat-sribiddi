package com.rft.chat_sribiddi.modelcatalog;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.models")
public class ModelCatalogProperties {

  private List<ModelOption> openai =
      new ArrayList<>(
          List.of(
              new ModelOption("gpt-5.2-2025-12-11", "gpt-5.2"),
              new ModelOption("gpt-5-mini-2025-08-07", "gpt-5-mini"),
              new ModelOption("gpt-5-nano-2025-08-07", "gpt-5-nano")));

  private List<ModelOption> google =
      new ArrayList<>(
          List.of(
              new ModelOption("gemini-3-flash-preview", "Gemini 2.5 flash"),
              new ModelOption("gemini-1.5-pro", "Gemini 1.5 Pro"),
              new ModelOption(
                  "gemini-2.0-flash-lite-preview-02-05", "Gemini 2.0 Flash Lite (preview)")));

  @Getter
  @Setter
  @AllArgsConstructor
  public static class ModelOption {
    private String id;
    private String label;
  }
}
