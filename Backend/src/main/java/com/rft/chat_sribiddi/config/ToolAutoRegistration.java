package com.rft.chat_sribiddi.config;

import com.rft.chat_sribiddi.tools.AgentPlanTool;
import com.rft.chat_sribiddi.tools.CalendarFreeBusyTool;
import com.rft.chat_sribiddi.tools.CalendarListEventsTool;
import com.rft.chat_sribiddi.tools.CalculatorTool;
import com.rft.chat_sribiddi.tools.ChartGeneratorTool;
import com.rft.chat_sribiddi.tools.CurrentDateTimeTool;
import com.rft.chat_sribiddi.tools.DocumentAnalysisTool;
import com.rft.chat_sribiddi.tools.GmailSearchTool;
import com.rft.chat_sribiddi.tools.GithubIssueSearchTool;
import com.rft.chat_sribiddi.tools.GoogleDriveTool;
import com.rft.chat_sribiddi.tools.ImageGenerationTool;
import com.rft.chat_sribiddi.tools.PythonInterpreterTool;
import com.rft.chat_sribiddi.tools.SpreadsheetAnalysisTool;
import com.rft.chat_sribiddi.tools.UrlFetcherTool;
import com.rft.chat_sribiddi.tools.WebResearchTool;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolRegistry;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialProperties;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialResolver;
import com.rft.chat_sribiddi.websearch.BraveSearchChatToolAdapter;
import com.rft.chat_sribiddi.websearch.BraveSearchTool;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import com.rft.chat_sribiddi.connectors.ConnectorAccountService;
import com.rft.chat_sribiddi.keyring.ApiKeyService;
import jakarta.annotation.PostConstruct;
import java.net.http.HttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
  private final ChartGeneratorTool chartGeneratorTool;
  private final AgentPlanTool agentPlanTool;
  private final DocumentAnalysisTool documentAnalysisTool;
  private final WebSearchProperties webSearchProperties;
  private final ToolCredentialResolver toolCredentialResolver;
  private final ToolCredentialProperties toolCredentialProperties;
  private final ConnectorAccountService connectorAccountService;
  private final ApiKeyService apiKeyService;
  private final HttpClient httpClient;

  @Value("${app.tools.python-enabled:false}")
  private boolean pythonEnabled;

  @PostConstruct
  void registerTools() {
    register(calculatorTool);
    register(urlFetcherTool);
    register(currentDateTimeTool);
    if (pythonEnabled) {
      register(pythonInterpreterTool);
    }
    register(chartGeneratorTool);
    register(agentPlanTool);
    register(documentAnalysisTool);
    register(new GoogleDriveTool());
    register(new GmailSearchTool("", connectorAccountService, httpClient));
    register(new CalendarListEventsTool("", connectorAccountService, httpClient));
    register(new CalendarFreeBusyTool("", connectorAccountService, httpClient));
    register(new SpreadsheetAnalysisTool());
    register(new WebResearchTool("", apiKeyService, webSearchProperties, httpClient));
    register(new ImageGenerationTool("", toolCredentialResolver, toolCredentialProperties));
    register(new GithubIssueSearchTool("", toolCredentialResolver, httpClient));
    // Always register BraveSearch so per-user keys work via wireTools()
    register(new BraveSearchChatToolAdapter(
        new BraveSearchTool(webSearchProperties.getApiKey(), httpClient, webSearchProperties)));

  }

  private void register(ChatTool tool) {
    toolRegistry.register(tool);
  }
}
