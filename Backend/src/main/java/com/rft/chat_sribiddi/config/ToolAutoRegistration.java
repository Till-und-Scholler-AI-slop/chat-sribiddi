package com.rft.chat_sribiddi.config;

import com.rft.chat_sribiddi.tools.CalculatorTool;
import com.rft.chat_sribiddi.tools.CurrentDateTimeTool;
import com.rft.chat_sribiddi.tools.PythonInterpreterTool;
import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.websearch.BraveSearchChatToolAdapter;
import com.rft.chat_sribiddi.websearch.BraveSearchTool;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Auto-registers all ChatTool implementations with the ToolRegistry on startup.
 */
@Component
@RequiredArgsConstructor
public class ToolAutoRegistration {

  private final ToolRegistry toolRegistry;
  private final CalculatorTool calculatorTool;
  private final UrlFetcherTool urlFetcherTool;
  private final CurrentDateTimeTool currentDateTimeTool;
  private final PythonInterpreterTool pythonInterpreterTool;
  private final WebSearchProperties webSearchProperties;

  @PostConstruct
  void registerTools() {
    register(calculatorTool);
    register(urlFetcherTool);
    register(currentDateTimeTool);
    register(pythonInterpreterTool);
    // Only register Brave Search if a default API key is configured
    String apiKey = System.getenv("BRAVE_SEARCH_API_KEY");
    if (apiKey != null && !apiKey.isBlank()) {
      register(new BraveSearchChatToolAdapter(
          new BraveSearchTool(apiKey, java.net.http.HttpClient.newHttpClient(), webSearchProperties)));
    }
  }

  private void register(ChatTool tool) {
    toolRegistry.register(tool);
  }
}
