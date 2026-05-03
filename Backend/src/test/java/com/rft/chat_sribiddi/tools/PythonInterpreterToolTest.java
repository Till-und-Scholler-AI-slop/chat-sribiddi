package com.rft.chat_sribiddi.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PythonInterpreterToolTest {

  private final PythonSandboxProperties properties = new PythonSandboxProperties();
  private final PythonInterpreterTool tool = new PythonInterpreterTool(properties);

  @Test
  void execute_rejectsOverlongCodeBeforeStartingDocker() {
    properties.setMaxCodeLength(3);

    var result = tool.execute("user-1", Map.of("code", "print('too long')"));

    assertThat(result.success()).isFalse();
    assertThat(result.error()).contains("maximum length");
  }

  @Test
  void buildCommand_usesDockerIsolationOptions() {
    assertThat(tool.buildCommand())
        .containsSubsequence("docker", "run")
        .contains("--rm", "--interactive", "--read-only")
        .containsSubsequence("--network", "none")
        .containsSubsequence("--memory", "128m")
        .containsSubsequence("--cpus", "0.5")
        .containsSubsequence("--pids-limit", "64")
        .containsSubsequence("--security-opt", "no-new-privileges")
        .containsSubsequence("--cap-drop", "ALL")
        .containsSubsequence("--user", "65534:65534")
        .containsSubsequence("python:3.13-alpine", "python3", "-I", "-S", "-");
  }

  @Test
  void sanitizeOutput_removesAnsiEscapeSequences() {
    String output = tool.sanitizeOutput("\u001B[31mred\u001B[0m plain");

    assertThat(output).isEqualTo("red plain");
  }
}
