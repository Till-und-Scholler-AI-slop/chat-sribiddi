package com.rft.chat_sribiddi.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CurrentDateTimeToolTest {

  private CurrentDateTimeTool tool;

  @BeforeEach
  void setUp() {
    tool = new CurrentDateTimeTool();
  }

  @Test
  void getCurrentDateTime_returnsNonBlankString() {
    String result = tool.getCurrentDateTime();

    assertThat(result).isNotBlank();
  }

  @Test
  void getCurrentDateTime_returnsCurrentYear() {
    String result = tool.getCurrentDateTime();
    String currentYear = String.valueOf(ZonedDateTime.now().getYear());

    assertThat(result).contains(currentYear);
  }

  @Test
  void getCurrentDateTime_returnsFormattedDateWithTwoCOMmasAndTime() {
    String result = tool.getCurrentDateTime();

    // The format is "EEEE, MMMM d, yyyy HH:mm:ss z"
    // "Wednesday, March 18, 2026 14:54:44 UTC" — two commas (after day name and after year)
    assertThat(result.chars().filter(c -> c == ',').count()).isEqualTo(2);
    assertThat(result).contains(":");
  }

  @Test
  void getCurrentDateTime_matchesExpectedPattern() {
    String result = tool.getCurrentDateTime();

    // Format: "EEEE, MMMM d, yyyy HH:mm:ss z" with Locale.ENGLISH
    // Day and month names are ASCII letters; zone abbreviation is non-whitespace
    assertThat(result).matches(
        "[A-Za-z]+, [A-Za-z]+ \\d{1,2}, \\d{4} \\d{2}:\\d{2}:\\d{2} \\S+");
  }
}
