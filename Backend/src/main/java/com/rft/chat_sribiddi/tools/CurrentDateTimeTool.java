package com.rft.chat_sribiddi.tools;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Spring AI tool that returns the current date and time.
 *
 * <p>This tool is injected unconditionally into every chat request so that all AI models have
 * access to accurate, up-to-date date/time information regardless of their training cutoff.
 */
public class CurrentDateTimeTool {

  @Tool(
      description =
          "Get the current date and time in the server's time zone. "
              + "Use this tool whenever the user asks about the current date, time, "
              + "day of the week, or any other time-sensitive information.")
  public String getCurrentDateTime() {
    return ZonedDateTime.now()
        .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy HH:mm:ss z"));
  }
}
