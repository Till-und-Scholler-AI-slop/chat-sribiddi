package com.rft.chat_sribiddi.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.tools.api.ChatTool;
import com.rft.chat_sribiddi.tools.api.ToolResult;
import com.rft.chat_sribiddi.tools.api.ToolRiskLevel;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialProvider;
import com.rft.chat_sribiddi.tools.credentials.ToolCredentialResolver;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class GithubIssueSearchTool implements ChatTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String userId;
  private final ToolCredentialResolver credentialResolver;
  private final HttpClient httpClient;

  public GithubIssueSearchTool(
      String userId, ToolCredentialResolver credentialResolver, HttpClient httpClient) {
    this.userId = userId;
    this.credentialResolver = credentialResolver;
    this.httpClient = httpClient;
  }

  @Override
  public String getName() {
    return "github-issue-search";
  }

  @Override
  public String getDisplayName() {
    return "GitHub Issue Search";
  }

  @Override
  public boolean requiresUserConfig() {
    return true;
  }

  @Override
  public ToolRiskLevel riskLevel() {
    return ToolRiskLevel.HIGH;
  }

  @Override
  public ToolResult execute(String userId, Map<String, Object> parameters) {
    return ToolResult.success(
        searchIssues(
            stringValue(parameters, "query"),
            stringValue(parameters, "repo"),
            stringValue(parameters, "type"),
            intValue(parameters, "limit", 5)));
  }

  @Tool(
      description =
          "Search GitHub issues and pull requests. Read-only. Optional repo format: owner/name.")
  public String searchIssues(
      @ToolParam(description = "Search query text") String query,
      @ToolParam(description = "Optional repository in owner/name format") String repo,
      @ToolParam(description = "issue, pr, or all") String type,
      @ToolParam(description = "Maximum number of results, 1-10") Integer limit) {
    if (query == null || query.isBlank()) {
      return unavailable("MISSING_QUERY", "GitHub search needs a query.");
    }
    String token = credentialResolver.resolve(userId, ToolCredentialProvider.GITHUB).orElse(null);
    if (token == null || token.isBlank()) {
      return unavailable(
          "GITHUB_UNCONNECTED",
          "GitHub search is unavailable until GitHub OAuth or a personal access token is configured.");
    }
    String normalizedType = type == null ? "all" : type.trim().toLowerCase();
    int requestedLimit = Math.max(1, Math.min(limit == null ? 5 : limit, 10));
    String qualifiedQuery = query.trim();
    if (repo != null && !repo.isBlank()) {
      qualifiedQuery += " repo:" + repo.trim();
    }
    if ("issue".equals(normalizedType)) {
      qualifiedQuery += " is:issue";
    } else if ("pr".equals(normalizedType) || "pull_request".equals(normalizedType)) {
      qualifiedQuery += " is:pr";
    }

    String url =
        "https://api.github.com/search/issues?q=%s&per_page=%d"
            .formatted(encode(qualifiedQuery), requestedLimit);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        return unavailable("GITHUB_SEARCH_FAILED", response.body());
      }
      JsonNode root = MAPPER.readTree(response.body());
      List<Map<String, Object>> items = new ArrayList<>();
      for (JsonNode item : root.path("items")) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", item.path("title").asText(""));
        result.put("url", item.path("html_url").asText(""));
        result.put("state", item.path("state").asText(""));
        result.put("number", item.path("number").asInt());
        result.put("repositoryUrl", item.path("repository_url").asText(""));
        result.put("pullRequest", item.has("pull_request"));
        items.add(result);
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("type", "github");
      result.put("query", qualifiedQuery);
      result.put("totalCount", root.path("total_count").asInt());
      result.put("items", items);
      return MAPPER.writeValueAsString(result);
    } catch (IOException e) {
      return unavailable("GITHUB_SEARCH_FAILED", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unavailable("GITHUB_SEARCH_INTERRUPTED", "GitHub search was interrupted.");
    }
  }

  private static String unavailable(String code, String message) {
    try {
      return MAPPER.writeValueAsString(
          Map.of("type", "github", "errorCode", code, "message", message == null ? "" : message));
    } catch (IOException _) {
      return message == null ? code : message;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String stringValue(Map<String, Object> parameters, String key) {
    Object value = parameters == null ? null : parameters.get(key);
    return value == null ? null : value.toString();
  }

  private static int intValue(Map<String, Object> parameters, String key, int fallback) {
    Object value = parameters == null ? null : parameters.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(value.toString());
    } catch (NumberFormatException _) {
      return fallback;
    }
  }
}
