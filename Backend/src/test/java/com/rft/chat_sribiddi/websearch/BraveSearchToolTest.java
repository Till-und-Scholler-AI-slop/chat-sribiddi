package com.rft.chat_sribiddi.websearch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BraveSearchToolTest {

  @Test
  void searchWeb_returnsGracefulError_whenApiFails() {
    BraveSearchTool tool = new BraveSearchTool("invalid-key");
    String result = tool.searchWeb("test query");
    // With an invalid key, the tool should return a user-friendly error, not throw
    assertThat(result).isNotNull().isNotEmpty();
    assertThat(result).containsAnyOf("failed", "No web results", "Web search");
  }

  @Test
  void searchWeb_handlesEmptyQuery() {
    BraveSearchTool tool = new BraveSearchTool("test-key");
    String result = tool.searchWeb("");
    // Should not throw; returns some result or error message
    assertThat(result).isNotNull();
  }

  @Test
  void searchWeb_handlesSpecialCharactersInQuery() {
    BraveSearchTool tool = new BraveSearchTool("test-key");
    // URL-unsafe characters should be encoded properly, not cause an exception
    String result = tool.searchWeb("hello world & foo=bar <script>");
    assertThat(result).isNotNull();
  }

  @Test
  void constructor_acceptsApiKey() {
    BraveSearchTool tool = new BraveSearchTool("my-api-key");
    assertThat(tool).isNotNull();
  }
}
