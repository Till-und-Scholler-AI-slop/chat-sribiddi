package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Calculator tool that evaluates arithmetic expressions safely using a recursive descent parser.
 * Supports: +, -, *, /, ^ (power), % (modulo), parentheses, decimal numbers.
 */
@Component
public class CalculatorTool implements ChatTool {

  private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

  private static final Set<Character> ALLOWED_CHARS =
      Set.of(
          '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
          '.', '+', '-', '*', '/', '^', '(', ')', ' ', '%');

  private static final long EVAL_TIMEOUT_MS = 3000;

  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r);
    t.setDaemon(true);
    return t;
  });

  @PreDestroy
  void shutdown() {
    EXECUTOR.shutdownNow();
  }

  @Override
  public String getName() {
    return "calculator";
  }

  @Override
  public String getDisplayName() {
    return "Calculator";
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    String expression =
        parameters != null && parameters.containsKey("expression")
            ? parameters.get("expression").toString()
            : null;
    if (expression == null || expression.isBlank()) {
      return ToolResult.failure("No expression provided.");
    }
    return evaluate(expression.trim());
  }

  @Tool(
      description =
          "Evaluate an arithmetic expression. Supports +, -, *, /, ^, parentheses, "
              + "modulo (%), and decimal numbers. Returns the numeric result.")
  public String calculate(
      @ToolParam(description = "The arithmetic expression to evaluate, e.g. '2 + 3 * (4 - 1)'")
          String expression) {
    ToolResult result = evaluate(expression);
    return result.success() ? result.output() : result.error();
  }

  private ToolResult evaluate(String expression) {
    if (!isValid(expression)) {
      return ToolResult.failure(
          "Invalid expression. Only digits, operators (+, -, *, /, ^, %), parentheses, and decimal points are allowed.");
    }

    String sanitized = expression.replaceAll("\\s+", "");
    if (sanitized.isEmpty()) {
      return ToolResult.failure("Expression is empty after removing whitespace.");
    }

    Future<Double> future = EXECUTOR.submit(() -> {
      Parser parser = new Parser(sanitized);
      return parser.parse();
    });

    try {
      double num = future.get(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (!Double.isFinite(num)) {
        return ToolResult.failure("Result is not a finite number.");
      }
      if (num == Math.floor(num) && !Double.isInfinite(num) && Math.abs(num) < 1e15) {
        return ToolResult.success(String.format("%.0f", num));
      }
      return ToolResult.success(Double.toString(num));
    } catch (TimeoutException e) {
      future.cancel(true);
      return ToolResult.failure("Calculation timed out.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      return ToolResult.failure("Calculation was interrupted.");
    } catch (Exception e) {
      log.warn("Calculation failed for expression: {}", expression, e);
      return ToolResult.failure("Could not evaluate expression: " + e.getMessage());
    }
  }

  private boolean isValid(String expression) {
    for (int i = 0; i < expression.length(); i++) {
      if (!ALLOWED_CHARS.contains(expression.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  // --- Recursive Descent Parser ---

  private static class Parser {
    private final String input;
    private int pos;

    Parser(String input) {
      this.input = input;
      this.pos = 0;
    }

    double parse() {
      double result = parseAddSub();
      if (pos < input.length()) {
        throw new ArithmeticException("Unexpected character at position " + pos + ": " + input.charAt(pos));
      }
      return result;
    }

    private double parseAddSub() {
      double result = parseMulDiv();
      while (pos < input.length()) {
        char c = input.charAt(pos);
        if (c == '+') {
          pos++;
          result += parseMulDiv();
        } else if (c == '-') {
          pos++;
          result -= parseMulDiv();
        } else {
          break;
        }
      }
      return result;
    }

    private double parseMulDiv() {
      double result = parsePower();
      while (pos < input.length()) {
        char c = input.charAt(pos);
        if (c == '*') {
          pos++;
          result *= parsePower();
        } else if (c == '/') {
          pos++;
          double divisor = parsePower();
          if (divisor == 0) throw new ArithmeticException("Division by zero");
          result /= divisor;
        } else if (c == '%') {
          pos++;
          double modulus = parsePower();
          if (modulus == 0) throw new ArithmeticException("Division by zero (modulo)");
          result %= modulus;
        } else {
          break;
        }
      }
      return result;
    }

    private double parsePower() {
      double result = parseUnary();
      if (pos < input.length() && input.charAt(pos) == '^') {
        pos++;
        double exponent = parsePower(); // right-associative
        result = Math.pow(result, exponent);
      }
      return result;
    }

    private double parseUnary() {
      if (pos < input.length() && input.charAt(pos) == '-') {
        pos++;
        return -parseUnary();
      }
      if (pos < input.length() && input.charAt(pos) == '+') {
        pos++;
        return parseUnary();
      }
      return parsePrimary();
    }

    private double parsePrimary() {
      if (pos < input.length() && input.charAt(pos) == '(') {
        pos++; // skip '('
        double result = parseAddSub();
        if (pos >= input.length() || input.charAt(pos) != ')') {
          throw new ArithmeticException("Missing closing parenthesis");
        }
        pos++; // skip ')'
        return result;
      }
      return parseNumber();
    }

    private double parseNumber() {
      int start = pos;
      while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
        pos++;
      }
      if (start == pos) {
        throw new ArithmeticException(
            pos < input.length()
                ? "Unexpected character: " + input.charAt(pos)
                : "Unexpected end of expression");
      }
      return Double.parseDouble(input.substring(start, pos));
    }
  }
}
