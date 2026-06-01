package com.rft.chat_sribiddi.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ChartGeneratorTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String getName() {
    return "chart-generator";
  }

  @Override
  public String getDisplayName() {
    return "Chart Generator";
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    return ToolResult.success(
        generateChart(
            stringValue(parameters, "title"),
            stringValue(parameters, "labelsCsv"),
            stringValue(parameters, "valuesCsv"),
            stringValue(parameters, "chartType")));
  }

  @Tool(
      description =
          "Generate a small SVG chart from comma-separated labels and numeric values. No external data sources are accessed.")
  public String generateChart(
      @ToolParam(description = "Chart title") String title,
      @ToolParam(description = "Comma-separated labels, max 12") String labelsCsv,
      @ToolParam(description = "Comma-separated numeric values, max 12") String valuesCsv,
      @ToolParam(description = "bar or line") String chartType) {
    List<String> labels = parseLabels(labelsCsv);
    List<Double> values = parseValues(valuesCsv);
    int count = Math.min(Math.min(labels.size(), values.size()), 12);
    if (count == 0) {
      return json(Map.of("type", "chart", "errorCode", "MISSING_DATA", "message", "No chart data provided."));
    }
    labels = new ArrayList<>(labels.subList(0, count));
    values = new ArrayList<>(values.subList(0, count));
    String resolvedTitle = title == null || title.isBlank() ? "Generated chart" : title.trim();
    String resolvedType =
        "line".equalsIgnoreCase(chartType) ? "line" : "bar";

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "chart");
    result.put("chartType", resolvedType);
    result.put("title", resolvedTitle);
    result.put("labels", labels);
    result.put("values", values);
    result.put("svg", buildSvg(resolvedTitle, labels, values, resolvedType));
    result.put("summary", summarize(labels, values));
    return json(result);
  }

  private static String buildSvg(
      String title, List<String> labels, List<Double> values, String chartType) {
    double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
    max = max <= 0 ? 1.0 : max;
    int width = 640;
    int left = 48;
    int bottom = 310;
    int plotHeight = 220;
    int barGap = 10;
    int barWidth = Math.max(18, (width - left - 36 - (labels.size() - 1) * barGap) / labels.size());
    StringBuilder svg = new StringBuilder();
    svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 640 360\" role=\"img\">");
    svg.append("<rect width=\"640\" height=\"360\" fill=\"white\"/>");
    svg.append("<text x=\"32\" y=\"36\" font-family=\"Inter, sans-serif\" font-size=\"20\" fill=\"#2f2a24\">")
        .append(escape(title))
        .append("</text>");
    svg.append("<line x1=\"48\" y1=\"310\" x2=\"612\" y2=\"310\" stroke=\"#d8d0c5\"/>");
    svg.append("<line x1=\"48\" y1=\"84\" x2=\"48\" y2=\"310\" stroke=\"#d8d0c5\"/>");
    if ("line".equals(chartType)) {
      svg.append("<polyline fill=\"none\" stroke=\"#2f8f57\" stroke-width=\"4\" points=\"");
      for (int i = 0; i < labels.size(); i++) {
        int x = left + i * (barWidth + barGap) + barWidth / 2;
        int y = bottom - (int) Math.round((values.get(i) / max) * plotHeight);
        svg.append(x).append(',').append(y).append(' ');
      }
      svg.append("\"/>");
    }
    for (int i = 0; i < labels.size(); i++) {
      int x = left + i * (barWidth + barGap);
      int barHeight = (int) Math.round((values.get(i) / max) * plotHeight);
      int y = bottom - barHeight;
      if (!"line".equals(chartType)) {
        svg.append("<rect x=\"")
            .append(x)
            .append("\" y=\"")
            .append(y)
            .append("\" width=\"")
            .append(barWidth)
            .append("\" height=\"")
            .append(barHeight)
            .append("\" rx=\"4\" fill=\"#2f8f57\"/>");
      }
      svg.append("<text x=\"")
          .append(x)
          .append("\" y=\"334\" font-family=\"Inter, sans-serif\" font-size=\"11\" fill=\"#746d63\">")
          .append(escape(labels.get(i)))
          .append("</text>");
    }
    svg.append("</svg>");
    return svg.toString();
  }

  private static String summarize(List<String> labels, List<Double> values) {
    int maxIndex = 0;
    for (int i = 1; i < values.size(); i++) {
      if (values.get(i) > values.get(maxIndex)) {
        maxIndex = i;
      }
    }
    return "Highest value: %s (%.2f).".formatted(labels.get(maxIndex), values.get(maxIndex));
  }

  private static List<String> parseLabels(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    List<String> labels = new ArrayList<>();
    for (String part : csv.split(",")) {
      String label = part.trim();
      if (!label.isEmpty()) {
        labels.add(label);
      }
    }
    return labels;
  }

  private static List<Double> parseValues(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    List<Double> values = new ArrayList<>();
    for (String part : csv.split(",")) {
      try {
        values.add(Double.parseDouble(part.trim().toLowerCase(Locale.ROOT)));
      } catch (NumberFormatException _) {
        values.add(0.0);
      }
    }
    return values;
  }

  private static String json(Map<String, Object> result) {
    try {
      return MAPPER.writeValueAsString(result);
    } catch (IOException _) {
      return "{}";
    }
  }

  private static String escape(String value) {
    return value == null
        ? ""
        : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String stringValue(Map<String, Object> parameters, String key) {
    Object value = parameters == null ? null : parameters.get(key);
    return value == null ? null : value.toString();
  }
}
