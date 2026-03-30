package com.rft.chat_sribiddi.chat.toolcall;

import com.rft.chat_sribiddi.websearch.BraveSearchTool;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class LoggedBraveSearchTool {

  private static final String TOOL_NAME = "BraveSearchTool.searchWeb";

  private final BraveSearchTool delegate;
  private final ToolExecutionCollector collector;

  public LoggedBraveSearchTool(BraveSearchTool delegate, ToolExecutionCollector collector) {
    this.delegate = delegate;
    this.collector = collector;
  }

  @Tool(
      description =
          "Search the web for current information using Brave Search. "
              + "Use this tool when the user asks about recent events, real-time data, "
              + "or any topic that may require up-to-date information from the internet.")
  public String searchWeb(
      @ToolParam(description = "The search query to look up on the web") String query) {
    return collector.record(TOOL_NAME, Map.of("query", query), () -> delegate.searchWeb(query));
  }
}
