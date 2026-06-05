package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.access.AccessProperties;
import com.rft.chat_sribiddi.access.CredentialAccessService.ResolvedCredential;
import com.rft.chat_sribiddi.auth.CredentialSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleOAuthChatModelTest {

  @Test
  void call_postsBearerRequestAndParsesText() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    GoogleOAuthChatModel model =
        new GoogleOAuthChatModel(credential(), builder.build(), new ObjectMapper());
    server
        .expect(
            requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
        .andExpect(method(POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
        .andExpect(header("x-goog-user-project", "quota-project"))
        .andExpect(
            content()
                .json(
                    """
                    {
                      "systemInstruction": { "parts": [ { "text": "system" } ] },
                      "contents": [
                        { "role": "user", "parts": [ { "text": "hello" } ] }
                      ],
                      "generationConfig": { "temperature": 0.2, "maxOutputTokens": 64 }
                    }
                    """))
        .andRespond(
            withSuccess(
                """
                {
                  "candidates": [
                    { "content": { "parts": [ { "text": "hi there" } ] } }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    var response =
        model.call(
            new Prompt(
                List.of(new SystemMessage("system"), new UserMessage("hello")),
                ChatOptions.builder().model("gemini-test").temperature(0.2).maxTokens(64).build()));

    assertThat(response.getResult().getOutput().getText()).isEqualTo("hi there");
    server.verify();
  }

  private ResolvedCredential credential() {
    return new ResolvedCredential(
        null,
        CredentialSource.USER_OAUTH,
        new AccessProperties().authenticatedLimits(),
        "access-token",
        "refresh-token",
        "client-id",
        "client-secret",
        "quota-project",
        Instant.now().plusSeconds(3600),
        "google",
        42L);
  }
}
