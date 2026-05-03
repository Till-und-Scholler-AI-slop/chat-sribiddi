package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GoogleProviderPropertiesTest {

  @Test
  void defaultTimeout_is120Seconds() {
    GoogleProviderProperties properties = new GoogleProviderProperties();

    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void setTimeout_rejectsNullValue() {
    GoogleProviderProperties properties = new GoogleProviderProperties();

    assertThatThrownBy(() -> properties.setTimeout(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be null");
  }

  @Test
  void setTimeout_rejectsOverflowValue() {
    GoogleProviderProperties properties = new GoogleProviderProperties();

    assertThatThrownBy(() -> properties.setTimeout(Duration.ofDays(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be between 1ms");
  }

  @Test
  void setTimeout_rejectsNonPositiveValue() {
    GoogleProviderProperties properties = new GoogleProviderProperties();

    assertThatThrownBy(() -> properties.setTimeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be between 1ms");
  }

  @Test
  void setTimeout_rejectsMillisConversionOverflow() {
    GoogleProviderProperties properties = new GoogleProviderProperties();

    assertThatThrownBy(() -> properties.setTimeout(Duration.ofSeconds(Long.MAX_VALUE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large to be represented in milliseconds");
  }
}
