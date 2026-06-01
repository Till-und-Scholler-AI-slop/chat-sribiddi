package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
import java.util.Map;

public class GoogleDriveTool implements ChatTool {

  @Override
  public String getName() {
    return "google-drive";
  }

  @Override
  public String getDisplayName() {
    return "Google Drive";
  }

  @Override
  public String getDescription() {
    return "Import selected Google Drive files into the chat document context.";
  }

  @Override
  public boolean requiresUserConfig() {
    return true;
  }

  @Override
  public ToolRiskLevel riskLevel() {
    return ToolRiskLevel.HIGH;
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    return ToolResult.failure("Google Drive imports are initiated from the chat source picker.");
  }
}
