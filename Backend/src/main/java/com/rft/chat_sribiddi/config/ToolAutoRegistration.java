package com.rft.chat_sribiddi.config;

import com.rft.chat_sribiddi.tools.CalculatorTool;
import com.rft.chat_sribiddi.tools.CurrentDateTimeTool;
import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
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

  @PostConstruct
  void registerTools() {
    register(calculatorTool);
    register(urlFetcherTool);
    register(currentDateTimeTool);
  }

  private void register(ChatTool tool) {
    toolRegistry.register(tool);
  }
}
