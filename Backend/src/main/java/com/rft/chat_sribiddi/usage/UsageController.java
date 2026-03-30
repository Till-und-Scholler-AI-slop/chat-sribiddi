package com.rft.chat_sribiddi.usage;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@RequestMapping("/api/usage")
@RestController
public class UsageController {

  private final UsageService usageService;

  @GetMapping
  public ResponseEntity<UsageResponseDTO> getUsage(
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "day") String groupBy) {
    validatePrincipal(principal);

    Instant effectiveFrom = from != null ? from : Instant.now().minus(30, ChronoUnit.DAYS);
    Instant effectiveTo = to != null ? to : Instant.now();

    return ResponseEntity.ok(
        usageService.getUsageStats(principal.getSubject(), effectiveFrom, effectiveTo, groupBy));
  }

  @GetMapping("/export")
  public ResponseEntity<String> exportCsv(
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to) {
    validatePrincipal(principal);

    Instant effectiveFrom = from != null ? from : Instant.now().minus(30, ChronoUnit.DAYS);
    Instant effectiveTo = to != null ? to : Instant.now();

    List<UsageStatsEntity> rows =
        usageService.getRawUsage(principal.getSubject(), effectiveFrom, effectiveTo);

    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    pw.println("timestamp,model,provider,input_tokens,output_tokens,total_tokens,estimated_cost,chat_id");
    for (UsageStatsEntity row : rows) {
      pw.printf("%s,%s,%s,%d,%d,%d,%.6f,%s%n",
          row.getCreatedAt(),
          escapeCsv(row.getModel()),
          escapeCsv(row.getProvider()),
          row.getInputTokens() != null ? row.getInputTokens() : 0,
          row.getOutputTokens() != null ? row.getOutputTokens() : 0,
          row.getTotalTokens() != null ? row.getTotalTokens() : 0,
          row.getEstimatedCost() != null ? row.getEstimatedCost() : 0.0,
          row.getChatId());
    }
    pw.flush();

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=usage-export.csv")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(sw.toString());
  }

  private void validatePrincipal(OidcUser principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
  }

  private static String escapeCsv(String value) {
    if (value == null) return "";
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }
}
