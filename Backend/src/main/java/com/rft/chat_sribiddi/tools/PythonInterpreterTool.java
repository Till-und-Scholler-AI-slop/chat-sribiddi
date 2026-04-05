package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Python interpreter tool that executes Python code via {@code python3 -c}.
 * Sandboxed by design: no external dependencies, only the python3 binary on the system.
 * Enforces a 15-second timeout and 10KB output limit.
 */
@Component
public class PythonInterpreterTool implements ChatTool {

  private static final Logger log = LoggerFactory.getLogger(PythonInterpreterTool.class);

  private static final long TIMEOUT_SECONDS = 15;
  private static final int MAX_OUTPUT_BYTES = 10_240; // 10 KB

  @Override
  public String getName() {
    return "python-interpreter";
  }

  @Override
  public String getDisplayName() {
    return "Python Interpreter";
  }

  @Override
  public String getDescription() {
    return "Execute Python code and return stdout/stderr output. "
        + "Useful for calculations, data processing, string manipulation, etc. "
        + "Timeout: 15 seconds, max output: 10KB.";
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    String code =
        parameters != null && parameters.containsKey("code")
            ? parameters.get("code").toString()
            : null;
    if (code == null || code.isBlank()) {
      return ToolResult.failure("No Python code provided.");
    }
    return executeCode(code.trim());
  }

  @Tool(
      description =
          "Execute Python code and return the output. "
              + "Supports stdout and stderr. Timeout: 15 seconds, max output: 10KB.")
  public String runPython(
      @ToolParam(description = "The Python code to execute") String code) {
    ToolResult result = executeCode(code);
    return result.success() ? result.output() : result.error();
  }

  private ToolResult executeCode(String code) {
    try {
      ProcessBuilder pb = new ProcessBuilder("python3", "-c", code);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
      boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (!finished) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        return ToolResult.failure("Python execution timed out after " + TIMEOUT_SECONDS + " seconds.");
      }

      int exitCode = process.exitValue();
      String outputText = new String(output, 0, Math.min(output.length, MAX_OUTPUT_BYTES));

      if (exitCode != 0) {
        return ToolResult.failure("Python exited with code " + exitCode + ":\n" + outputText);
      }

      if (output.length > MAX_OUTPUT_BYTES) {
        outputText += "\n\n[Output truncated at 10KB]";
      }

      return ToolResult.success(outputText.isEmpty() ? "(no output)" : outputText);
    } catch (IOException e) {
      return ToolResult.failure("Failed to start python3: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("Python execution was interrupted.");
    }
  }
}
