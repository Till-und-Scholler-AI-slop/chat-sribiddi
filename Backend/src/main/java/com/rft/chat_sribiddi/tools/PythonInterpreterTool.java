package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Python interpreter tool that executes Python code in an ephemeral Docker sandbox.
 *
 * <p>Defense-in-depth measures:
 * <ul>
 *   <li>Input validation: max code length</li>
 *   <li>Runs in a removed-after-exit Docker container</li>
 *   <li>No network, read-only root filesystem, dropped capabilities</li>
 *   <li>Limited CPU, memory, pids, and tmpfs</li>
 *   <li>{@code python3 -S} to skip site-packages</li>
 *   <li>Hard timeout with forced kill</li>
 *   <li>Output limit</li>
 *   <li>stderr captured, sanitized, and returned</li>
 * </ul>
 */
@Component
public class PythonInterpreterTool implements ChatTool {

  private static final Logger log = LoggerFactory.getLogger(PythonInterpreterTool.class);

  private static final Pattern ANSI_ESCAPE_PATTERN =
      Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");

  private final PythonSandboxProperties properties;
  private final ProcessStarter processStarter;

  @Autowired
  public PythonInterpreterTool(PythonSandboxProperties properties) {
    this(properties, processBuilder -> processBuilder.start());
  }

  PythonInterpreterTool(PythonSandboxProperties properties, ProcessStarter processStarter) {
    this.properties = properties;
    this.processStarter = processStarter;
  }

  @Override
  public String getName() {
    return "python-interpreter";
  }

  @Override
  public String getDisplayName() {
    return "Python Interpreter";
  }

  @Override
  public ToolRiskLevel riskLevel() {
    return ToolRiskLevel.HIGH;
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

  private ToolResult validateCode(String code) {
    if (code.length() > properties.getMaxCodeLength()) {
      return ToolResult.failure(
          "Code exceeds maximum length of %d characters.".formatted(properties.getMaxCodeLength()));
    }
    return null; // null means validation passed
  }

  private ToolResult executeCode(String code) {
    ToolResult validationError = validateCode(code);
    if (validationError != null) return validationError;

    try {
      ProcessBuilder pb = new ProcessBuilder(buildCommand());
      pb.environment().clear();
      pb.redirectErrorStream(true);
      Process process = processStarter.start(pb);

      LimitedOutputCollector outputCollector =
          new LimitedOutputCollector(process.getInputStream(), properties.getMaxOutputBytes());
      Thread outputThread = new Thread(outputCollector, "python-sandbox-output");
      outputThread.start();

      try (var stdin = process.getOutputStream()) {
        stdin.write(code.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        log.debug("Python sandbox closed stdin before code was fully written", e);
      }

      boolean finished = process.waitFor(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        return ToolResult.failure(
            "Python execution timed out after " + properties.getTimeout().toSeconds() + " seconds.");
      }

      outputThread.join(TimeUnit.SECONDS.toMillis(5));
      int exitCode = process.exitValue();
      String outputText =
          sanitizeOutput(new String(outputCollector.output(), StandardCharsets.UTF_8));

      if (exitCode != 0) {
        return ToolResult.failure("Python exited with code " + exitCode + ":\n" + outputText);
      }

      if (outputCollector.truncated()) {
        outputText += "\n\n[Output truncated at %d bytes]".formatted(properties.getMaxOutputBytes());
      }

      return ToolResult.success(outputText.isEmpty() ? "(no output)" : outputText);
    } catch (IOException e) {
      log.warn("Failed to start Docker Python sandbox", e);
      return ToolResult.failure("Python sandbox is unavailable on this host.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("Python execution was interrupted.");
    }
  }

  List<String> buildCommand() {
    List<String> command = new ArrayList<>();
    command.add(properties.getDockerBinary());
    command.add("run");
    command.add("--rm");
    command.add("--interactive");
    command.add("--network");
    command.add("none");
    command.add("--memory");
    command.add(properties.getMemory());
    command.add("--cpus");
    command.add(properties.getCpus());
    command.add("--pids-limit");
    command.add(String.valueOf(properties.getPidsLimit()));
    command.add("--read-only");
    command.add("--tmpfs");
    command.add(properties.getTmpfs());
    command.add("--security-opt");
    command.add("no-new-privileges");
    command.add("--cap-drop");
    command.add("ALL");
    command.add("--user");
    command.add("65534:65534");
    command.add(properties.getImage());
    command.add("python3");
    command.add("-I");
    command.add("-S");
    command.add("-");
    return command;
  }

  String sanitizeOutput(String output) {
    if (output == null || output.isEmpty()) {
      return "";
    }
    return ANSI_ESCAPE_PATTERN.matcher(output).replaceAll("");
  }

  interface ProcessStarter {
    Process start(ProcessBuilder processBuilder) throws IOException;
  }

  private static final class LimitedOutputCollector implements Runnable {
    private final InputStream inputStream;
    private final int maxBytes;
    private byte[] output = new byte[0];
    private boolean truncated;

    private LimitedOutputCollector(InputStream inputStream, int maxBytes) {
      this.inputStream = inputStream;
      this.maxBytes = maxBytes;
    }

    @Override
    public void run() {
      try {
        byte[] bytes = inputStream.readNBytes(maxBytes + 1);
        truncated = bytes.length > maxBytes;
        output = truncated ? java.util.Arrays.copyOf(bytes, maxBytes) : bytes;
      } catch (IOException e) {
        output = new byte[0];
      }
    }

    private byte[] output() {
      return output;
    }

    private boolean truncated() {
      return truncated;
    }
  }
}
