package com.rft.chat_sribiddi.tools;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Spring AI tool that returns the current date and time.
 *
 * <p>This tool is injected unconditionally into every chat request so that all AI models have
 * access to accurate, up-to-date date/time information regardless of their training cutoff.
 */
@Component
public class CurrentDateTimeTool {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy HH:mm:ss z", Locale.ENGLISH);

  @Tool(
      description =
          "Get the current date and time in the server's time zone. "
              + "Use this tool whenever the user asks about the current date, time, "
              + "day of the week, or any other time-sensitive information.")
  public String getCurrentDateTime() {
    return ZonedDateTime.now().format(FORMATTER);
  }
}
