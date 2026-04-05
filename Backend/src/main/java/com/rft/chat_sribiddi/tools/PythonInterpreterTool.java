package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Python interpreter tool that executes Python code in a sandboxed environment.
 *
 * <p>Defense-in-depth measures:
 * <ul>
 *   <li>Input validation: max code length, blocked dangerous keywords</li>
 *   <li>Runs as {@code nobody} via {@code runuser}</li>
 *   <li>Cleared environment variables</li>
 *   <li>Working directory set to {@code /tmp}</li>
 *   <li>{@code python3 -S} to skip site-packages</li>
 *   <li>15-second timeout with forced kill</li>
 *   <li>10KB output limit</li>
 *   <li>stderr captured and returned</li>
 * </ul>
 */
@Component
public class PythonInterpreterTool implements ChatTool {

  private static final Logger log = LoggerFactory.getLogger(PythonInterpreterTool.class);

  private static final long TIMEOUT_SECONDS = 15;
  private static final int MAX_OUTPUT_BYTES = 10_240; // 10 KB
  private static final int MAX_CODE_LENGTH = 10_000;

  private static final Set<String> BLOCKED_PATTERNS = Set.of(
      "import os", "subprocess", "socket", "exec(", "eval(",
      "__import__", "open(", "shutil", "pathlib", "sys.exit",
      "importlib", "importlib.import_module"
  );

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
    // Input validation
    if (code.length() > MAX_CODE_LENGTH) {
      return ToolResult.failure("Code exceeds maximum length of %d characters.".formatted(MAX_CODE_LENGTH));
    }
    String loweredCode = code.toLowerCase();
    for (String blocked : BLOCKED_PATTERNS) {
      if (loweredCode.contains(blocked.toLowerCase())) {
        return ToolResult.failure(
            "Code contains blocked keyword: '%s'. For security reasons, certain modules "
                + "and functions are not allowed.".formatted(blocked));
      }
    }

    try {
      // Build sandboxed command: run as nobody, skip site-packages
      ProcessBuilder pb = new ProcessBuilder(
          "runuser", "-u", "nobody", "--", "python3", "-S", "-c", code);
      pb.environment().clear();
      pb.directory(new File("/tmp"));
      pb.redirectErrorStream(true);
      Process process = pb.start();

      boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        return ToolResult.failure("Python execution timed out after " + TIMEOUT_SECONDS + " seconds.");
      }

      byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
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
      // Fallback to su if runuser is not available
      log.debug("runuser failed, falling back to su: {}", e.getMessage());
      return executeWithSu(code);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("Python execution was interrupted.");
    }
  }

  private ToolResult executeWithSu(String code) {
    try {
      // Escape single quotes in code for shell
      String escaped = code.replace("'", "'\\''");
      ProcessBuilder pb = new ProcessBuilder(
          "su", "-s", "/bin/sh", "nobody", "-c",
          "python3 -S -c '" + escaped + "'");
      pb.environment().clear();
      pb.directory(new File("/tmp"));
      pb.redirectErrorStream(true);
      Process process = pb.start();

      boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        return ToolResult.failure("Python execution timed out after " + TIMEOUT_SECONDS + " seconds.");
      }

      byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
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
