package com.rft.chat_sribiddi.tools;

import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Calculator tool that evaluates arithmetic expressions safely.
 * Supports: +, -, *, /, ^, parentheses, decimal numbers.
 */
@Component
public class CalculatorTool implements ChatTool {

  private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

  private static final Set<Character> ALLOWED_CHARS =
      Set.of(
          '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
          '.', '+', '-', '*', '/', '^', '(', ')', ' ', '%');

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private static final ScriptEngine ENGINE = new ScriptEngineManager().getEngineByName("js");

  @Override
  public String getName() {
    return "calculator";
  }

  @Override
  public String getDisplayName() {
    return "Calculator";
  }

  @Override
  public String getDescription() {
    return "Evaluate arithmetic expressions. Supports +, -, *, /, ^, parentheses, and decimal numbers. "
        + "Use this tool when the user asks you to calculate something.";
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
          "Invalid expression. Only digits, operators (+, -, *, /, ^, %%), parentheses, and decimal points are allowed.");
    }

    String sanitized = WHITESPACE.matcher(expression).replaceAll("").trim();
    if (sanitized.isEmpty()) {
      return ToolResult.failure("Expression is empty after removing whitespace.");
    }

    // Convert ^ to Math.pow for JS engine
    String jsExpression = convertPowerOperator(sanitized);

    try {
      // Execute with a timeout thread
      Object evalResult = timedEval(jsExpression, 3000);
      if (evalResult == null) {
        return ToolResult.failure("Calculation timed out.");
      }

      String resultStr = evalResult.toString();

      // Validate result is a finite number
      try {
        double num = Double.parseDouble(resultStr);
        if (!Double.isFinite(num)) {
          return ToolResult.failure("Result is not a finite number.");
        }
        // Format nicely: remove trailing zeros for whole numbers
        if (num == Math.floor(num) && !Double.isInfinite(num)) {
          return ToolResult.success(String.format("%.0f", num));
        }
        return ToolResult.success(resultStr);
      } catch (NumberFormatException e) {
        return ToolResult.failure("Result is not a valid number: " + resultStr);
      }
    } catch (TimeoutException e) {
      return ToolResult.failure("Calculation timed out.");
    } catch (ScriptException e) {
      log.warn("Calculation failed for expression: {}", expression, e);
      return ToolResult.failure("Could not evaluate expression: " + e.getMessage());
    }
  }

  private boolean isValid(String expression) {
    for (int i = 0; i < expression.length(); i++) {
      char c = expression.charAt(i);
      if (!ALLOWED_CHARS.contains(c)) {
        return false;
      }
    }
    return true;
  }

  private String convertPowerOperator(String expr) {
    // Simple conversion: a^b → Math.pow(a,b) for simple cases
    // For complex expressions, we'll handle single-level power operations
    int idx = expr.indexOf('^');
    if (idx < 0) return expr;

    String before = expr.substring(0, idx);
    String after = expr.substring(idx + 1);

    // Find the base (walk backwards from ^)
    String base = extractOperand(before, true);
    String rest = before.substring(0, before.length() - base.length());

    // Find the exponent (walk forwards from ^)
    String exponent = extractOperand(after, false);
    String remainder = after.substring(exponent.length());

    return rest + "Math.pow(" + base + "," + exponent + ")" + remainder;
  }

  private String extractOperand(String s, boolean reverse) {
    StringBuilder sb = new StringBuilder();
    int i = reverse ? s.length() - 1 : 0;
    int end = reverse ? -1 : s.length();
    int step = reverse ? -1 : 1;

    while (i != end) {
      char c = s.charAt(i);
      if (Character.isDigit(c) || c == '.') {
        sb.insert(reverse ? 0 : sb.length(), c);
      } else if ((c == '-' || c == '+') && sb.length() == 0) {
        sb.insert(reverse ? 0 : sb.length(), c);
      } else if (c == ')' || c == '(') {
        // Include parenthesized sub-expressions
        sb.insert(reverse ? 0 : sb.length(), c);
        if (c == ')') {
          int depth = 1;
          while (i != end && depth > 0) {
            i += step;
            if (i == end) break;
            char nc = s.charAt(i);
            sb.insert(reverse ? 0 : sb.length(), nc);
            if (nc == ')') depth++;
            else if (nc == '(') depth--;
          }
          if (i != end) i += step;
        } else {
          i += step;
        }
        continue;
      } else {
        break;
      }
      i += step;
    }

    return sb.toString();
  }

  private static Object timedEval(String expression, long timeoutMs)
      throws ScriptException, TimeoutException {
    Thread[] runner = new Thread[1];
    Object[] result = new Object[1];
    Throwable[] error = new Throwable[1];

    runner[0] =
        new Thread(
            () -> {
              try {
                synchronized (ENGINE) {
                  result[0] = ENGINE.eval(expression);
                }
              } catch (ScriptException e) {
                error[0] = e;
              }
            });

    runner[0].start();
    try {
      runner[0].join(timeoutMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TimeoutException();
    }

    if (runner[0].isAlive()) {
      runner[0].interrupt();
      throw new TimeoutException();
    }

    if (error[0] != null) {
      if (error[0] instanceof ScriptException se) throw se;
      throw new ScriptException(error[0].getMessage());
    }

    return result[0];
  }

  private static class TimeoutException extends Exception {}
}
