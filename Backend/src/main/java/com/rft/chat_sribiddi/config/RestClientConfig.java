package com.rft.chat_sribiddi.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provides the application-wide {@link RestClient} bean.
 *
 * <p>Spring Boot auto-configures a {@link RestClient.Builder} but never a ready-to-inject {@link
 * RestClient}, so components such as {@code ModelFetchService} that depend on a {@link RestClient}
 * would fail to start without this bean. The client is built on the JDK {@link HttpClient} with
 * explicit connect/read timeouts so outbound calls to provider APIs cannot hang indefinitely.
 */
@Configuration
public class RestClientConfig {

  @Bean
  public RestClient restClient() {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(30));
    return RestClient.builder().requestFactory(requestFactory).build();
  }
}
