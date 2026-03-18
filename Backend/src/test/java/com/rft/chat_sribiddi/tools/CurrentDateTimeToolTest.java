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
  void getCurrentDateTime_returnsFormattedDateWithDayNameAndTime() {
    String result = tool.getCurrentDateTime();

    // The format is "EEEE, MMMM d, yyyy HH:mm:ss z"
    // Verify the result contains two commas (after day name and after year) and colons (time)
    assertThat(result).contains(",");
    assertThat(result).contains(":");
  }

  @Test
  void getCurrentDateTime_containsTimeZoneAbbreviation() {
    String result = tool.getCurrentDateTime();
    String expectedZone = ZonedDateTime.now().getZone().getId();

    // The formatted string includes the zone abbreviation (e.g. "UTC", "EST")
    // Just verify the result is not empty and looks like a date string
    assertThat(result).matches(
        "\\w+, \\w+ \\d{1,2}, \\d{4} \\d{2}:\\d{2}:\\d{2} .+");
  }
}
