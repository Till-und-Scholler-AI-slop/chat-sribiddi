package com.rft.chat_sribiddi.chat.toolcall;

import com.rft.chat_sribiddi.tools.PythonInterpreterTool;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class LoggedPythonInterpreterTool {

  private static final String TOOL_NAME = "PythonInterpreterTool.runPython";

  private final PythonInterpreterTool delegate;
  private final ToolExecutionCollector collector;

  public LoggedPythonInterpreterTool(PythonInterpreterTool delegate, ToolExecutionCollector collector) {
    this.delegate = delegate;
    this.collector = collector;
  }

  @Tool(
      description =
          "Execute Python code and return the output. "
              + "Supports stdout and stderr. Timeout: 15 seconds, max output: 10KB.")
  public String runPython(
      @ToolParam(description = "The Python code to execute") String code) {
    return collector.record(
        TOOL_NAME, Map.of("code", code), () -> delegate.runPython(code));
  }
}
