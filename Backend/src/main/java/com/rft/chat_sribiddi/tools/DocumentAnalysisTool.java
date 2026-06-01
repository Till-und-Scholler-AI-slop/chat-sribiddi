package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DocumentAnalysisTool implements ChatTool {

  @Override
  public String getName() {
    return "document-analysis";
  }

  @Override
  public String getDisplayName() {
    return "Document Analysis";
  }

  @Override
  public String getDescription() {
    return "Shows the document retrieval context used by the chat RAG pipeline.";
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    return ToolResult.failure("Document analysis is emitted by the RAG pipeline, not called directly.");
  }
}
