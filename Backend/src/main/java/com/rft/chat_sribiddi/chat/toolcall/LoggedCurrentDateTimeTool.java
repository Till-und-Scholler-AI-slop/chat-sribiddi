package com.rft.chat_sribiddi.chat.toolcall;

import com.rft.chat_sribiddi.tools.CurrentDateTimeTool;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;

public class LoggedCurrentDateTimeTool {

  private static final String TOOL_NAME = "CurrentDateTimeTool.getCurrentDateTime";

  private final CurrentDateTimeTool delegate;
  private final ToolExecutionCollector collector;

  public LoggedCurrentDateTimeTool(
      CurrentDateTimeTool delegate, ToolExecutionCollector collector) {
    this.delegate = delegate;
    this.collector = collector;
  }

  @Tool(
      description =
          "Get the current date and time in the server's time zone. "
              + "Use this tool whenever the user asks about the current date, time, "
              + "day of the week, or any other time-sensitive information.")
  public String getCurrentDateTime() {
    return collector.record(TOOL_NAME, Map.of(), delegate::getCurrentDateTime);
  }
}
