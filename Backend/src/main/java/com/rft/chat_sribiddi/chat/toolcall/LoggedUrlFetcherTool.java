package com.rft.chat_sribiddi.chat.toolcall;

import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class LoggedUrlFetcherTool {

  private static final String TOOL_NAME = "UrlFetcherTool.fetchUrl";

  private final UrlFetcherTool delegate;
  private final ToolExecutionCollector collector;

  public LoggedUrlFetcherTool(UrlFetcherTool delegate, ToolExecutionCollector collector) {
    this.delegate = delegate;
    this.collector = collector;
  }

  @Tool(
      description =
          "Fetch the content of a web page URL and return its text. "
              + "HTML is stripped; content is truncated at 50KB. Timeout: 10 seconds.")
  public String fetchUrl(
      @ToolParam(description = "The URL to fetch, e.g. 'https://example.com'") String url) {
    return collector.record(
        TOOL_NAME, Map.of("url", url), () -> delegate.fetchUrl(url));
  }
}
