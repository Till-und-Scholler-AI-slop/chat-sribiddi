package com.rft.chat_sribiddi.usage;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable token pricing per model (cost per 1 million tokens).
 *
 * <pre>
 * app:
 *   usage:
 *     prices:
 *       gpt-5.2:
 *         input: 2.50
 *         output: 10.00
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.usage")
public class UsageProperties {

  /** Default cost per 1M input tokens when a model has no specific pricing. */
  private double defaultInputPrice = 1.0;

  /** Default cost per 1M output tokens when a model has no specific pricing. */
  private double defaultOutputPrice = 3.0;

  /** Per-model pricing overrides (cost per 1M tokens). */
  private Map<String, ModelPrice> prices = new HashMap<>();

  @Getter
  @Setter
  public static class ModelPrice {
    private double input = 1.0;
    private double output = 3.0;
  }

  /**
   * Estimates cost in USD for the given token counts and model.
   */
  public double estimateCost(String model, Long inputTokens, Long outputTokens) {
    long in = inputTokens != null ? inputTokens : 0;
    long out = outputTokens != null ? outputTokens : 0;
    ModelPrice price = prices.get(model);
    double inputRate = price != null ? price.getInput() : defaultInputPrice;
    double outputRate = price != null ? price.getOutput() : defaultOutputPrice;
    return (in * inputRate + out * outputRate) / 1_000_000.0;
  }
}
