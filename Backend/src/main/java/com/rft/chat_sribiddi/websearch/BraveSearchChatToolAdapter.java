package com.rft.chat_sribiddi.websearch;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.util.Map;

/**
 * ChatTool adapter for BraveSearchTool.
 * Does not implement the generic execute() method directly since it requires
 * a user-specific API key — that resolution happens in ChatService.
 * This exists purely so Brave Search appears in the ToolRegistry and UI.
 */
public class BraveSearchChatToolAdapter implements ChatTool {

  private final BraveSearchTool delegate;

  public BraveSearchChatToolAdapter(BraveSearchTool delegate) {
    this.delegate = delegate;
  }

  @Override
  public String getName() {
    return "brave-search";
  }

  @Override
  public String getDisplayName() {
    return "Web Search (Brave)";
  }

  @Override
  public String getDescription() {
    return "Search the web for current information using Brave Search API. "
        + "Requires a Brave Search API key in your keyring.";
  }

  @Override
  public boolean requiresUserConfig() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    String query =
        parameters != null && parameters.containsKey("query")
            ? parameters.get("query").toString()
            : null;
    if (query == null || query.isBlank()) {
      return ToolResult.failure("No query provided.");
    }
    String result = delegate.searchWeb(query);
    return ToolResult.success(result);
  }
}
