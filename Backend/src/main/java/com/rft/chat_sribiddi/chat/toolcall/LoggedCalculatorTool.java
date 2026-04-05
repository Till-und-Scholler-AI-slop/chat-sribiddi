package com.rft.chat_sribiddi.chat.toolcall;

import com.rft.chat_sribiddi.tools.CalculatorTool;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class LoggedCalculatorTool {

  private static final String TOOL_NAME = "CalculatorTool.calculate";

  private final CalculatorTool delegate;
  private final ToolExecutionCollector collector;

  public LoggedCalculatorTool(CalculatorTool delegate, ToolExecutionCollector collector) {
    this.delegate = delegate;
    this.collector = collector;
  }

  @Tool(
      description =
          "Evaluate an arithmetic expression. Supports +, -, *, /, ^, parentheses, "
              + "modulo (%), and decimal numbers. Returns the numeric result.")
  public String calculate(
      @ToolParam(description = "The arithmetic expression to evaluate, e.g. '2 + 3 * (4 - 1)'")
          String expression) {
    return collector.record(
        TOOL_NAME, Map.of("expression", expression), () -> delegate.calculate(expression));
  }
}
