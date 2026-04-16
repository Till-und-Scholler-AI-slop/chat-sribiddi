package com.rft.chat_sribiddi.tools.api;

/**
 * Result of a tool execution.
 *
 * @param success whether the tool executed successfully
 * @param output  the result text to return to the AI model
 * @param error   error message if {@code success} is false
 */
public record ToolResult(boolean success, String output, String error) {

  public static ToolResult success(String output) {
    return new ToolResult(true, output, null);
  }

  public static ToolResult failure(String error) {
    return new ToolResult(false, null, error);
  }
}
