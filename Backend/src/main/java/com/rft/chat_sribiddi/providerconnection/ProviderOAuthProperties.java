package com.rft.chat_sribiddi.providerconnection;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.provider-oauth")
public class ProviderOAuthProperties {

  private Google google = new Google();

  @Getter
  @Setter
  public static class Google {
    private boolean enabled = true;
    private String clientId;
    private String clientSecret;
    private String userProject;
    private URI authorizationUri = URI.create("https://accounts.google.com/o/oauth2/v2/auth");
    private URI tokenUri = URI.create("https://oauth2.googleapis.com/token");
    private List<String> scopes =
        new ArrayList<>(
            List.of(
                "https://www.googleapis.com/auth/cloud-platform",
                "https://www.googleapis.com/auth/generative-language.retriever"));

    public boolean configured() {
      return hasText(clientId) && hasText(clientSecret);
    }

    private boolean hasText(String value) {
      return value != null && !value.isBlank();
    }
  }
}
