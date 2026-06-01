package com.rft.chat_sribiddi.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgentPlanTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String getName() {
    return "agent-plan";
  }

  @Override
  public String getDisplayName() {
    return "Agent Plan";
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    return ToolResult.success(
        updatePlan(
            intValue(parameters, "step", 1),
            intValue(parameters, "totalSteps", 1),
            stringValue(parameters, "currentStep"),
            stringValue(parameters, "completedSteps"),
            stringValue(parameters, "nextSteps")));
  }

  @Tool(
      description =
          "Report multi-step task progress to the user interface. This does not start a separate autonomous agent.")
  public String updatePlan(
      @ToolParam(description = "Current 1-based step number") Integer step,
      @ToolParam(description = "Total number of steps") Integer totalSteps,
      @ToolParam(description = "Current step title") String currentStep,
      @ToolParam(description = "Completed steps, one per line") String completedSteps,
      @ToolParam(description = "Upcoming steps, one per line") String nextSteps) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "agent-plan");
    result.put("step", Math.max(1, step == null ? 1 : step));
    result.put("totalSteps", Math.max(1, totalSteps == null ? 1 : totalSteps));
    result.put("currentStep", currentStep == null ? "" : currentStep.trim());
    result.put("completedSteps", lines(completedSteps));
    result.put("nextSteps", lines(nextSteps));
    try {
      return MAPPER.writeValueAsString(result);
    } catch (IOException _) {
      return "{}";
    }
  }

  private static List<String> lines(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split("\\R+"))
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  private static String stringValue(Map<String, Object> parameters, String key) {
    Object value = parameters == null ? null : parameters.get(key);
    return value == null ? null : value.toString();
  }

  private static int intValue(Map<String, Object> parameters, String key, int fallback) {
    Object value = parameters == null ? null : parameters.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(value.toString());
    } catch (NumberFormatException _) {
      return fallback;
    }
  }
}
