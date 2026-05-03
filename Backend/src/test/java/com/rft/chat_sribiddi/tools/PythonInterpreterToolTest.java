package com.rft.chat_sribiddi.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PythonInterpreterToolTest {

  private final PythonInterpreterTool tool = new PythonInterpreterTool();

  @Test
  void execute_blocksCompileByDefault() {
    var result = tool.execute("user-1", Map.of("code", "compile('1 + 1', '<x>', 'eval')"));

    assertThat(result.success()).isFalse();
    assertThat(result.error()).contains("compile(");
  }

  @Test
  void sanitizeOutput_removesAnsiEscapeSequences() {
    String output = tool.sanitizeOutput("\u001B[31mred\u001B[0m plain");

    assertThat(output).isEqualTo("red plain");
  }
}
