package com.rft.chat_sribiddi.tools.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.rft.chat_sribiddi.websearch.BraveSearchChatToolAdapter;
import com.rft.chat_sribiddi.websearch.BraveSearchTool;
import com.rft.chat_sribiddi.websearch.WebSearchProperties;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

class ChatToolTest {

  @Test
  void getDescription_usesSpringAiToolDefinitionDescription() {
    ChatTool tool = new AnnotatedChatTool();

    assertThat(tool.getDescription()).isEqualTo("Run the annotated test tool.");
  }

  @Test
  void getDescription_fallsBackToDisplayNameWhenNoSpringAiToolDefinitionExists() {
    ChatTool tool = new MetadataOnlyChatTool();

    assertThat(tool.getDescription()).isEqualTo("Metadata Only");
  }

  @Test
  void braveSearchAdapter_usesDelegateSpringAiToolDescription() {
    ChatTool tool =
        new BraveSearchChatToolAdapter(
            new BraveSearchTool("brave-key", HttpClient.newHttpClient(), new WebSearchProperties()));

    assertThat(tool.getDescription())
        .contains("Search the web for current information using Brave Search");
  }

  private static final class AnnotatedChatTool implements ChatTool {

    @Override
    public String getName() {
      return "annotated";
    }

    @Override
    public String getDisplayName() {
      return "Annotated";
    }

    @Override
    public ToolResult execute(String userId, Map<String, Object> parameters) {
      return ToolResult.success("ok");
    }

    @Tool(description = "Run the annotated test tool.")
    public String run() {
      return "ok";
    }
  }

  private static final class MetadataOnlyChatTool implements ChatTool {

    @Override
    public String getName() {
      return "metadata-only";
    }

    @Override
    public String getDisplayName() {
      return "Metadata Only";
    }

    @Override
    public ToolResult execute(String userId, Map<String, Object> parameters) {
      return ToolResult.success("ok");
    }
  }
}
