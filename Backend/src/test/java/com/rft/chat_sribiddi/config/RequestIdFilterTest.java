package com.rft.chat_sribiddi.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void doFilterInternal_reusesSafeIncomingRequestId() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chats");
    request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "req-12345678");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, new MockFilterChain());

    assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("req-12345678");
  }

  @Test
  void doFilterInternal_replacesUnsafeIncomingRequestId() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chats");
    request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "bad\nvalue");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilterInternal(request, response, new MockFilterChain());

    assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER))
        .isNotBlank()
        .isNotEqualTo("bad\nvalue");
  }
}
