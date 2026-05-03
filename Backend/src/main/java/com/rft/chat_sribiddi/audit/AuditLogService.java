package com.rft.chat_sribiddi.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rft.chat_sribiddi.auth.Plan;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

  public static final String PLAN_STATE_CHANGE = "PLAN_STATE_CHANGE";

  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;

  @Transactional
  public void recordPlanStateChange(
      Long userId,
      Plan previousPlan,
      Plan newPlan,
      String previousStatus,
      String newStatus,
      String source,
      String reason,
      Map<String, ?> metadata) {
    if (Objects.equals(previousPlan, newPlan) && Objects.equals(previousStatus, newStatus)) {
      return;
    }

    AuditLogEntity entry = new AuditLogEntity();
    entry.setUserId(userId);
    entry.setEventType(PLAN_STATE_CHANGE);
    entry.setSource(blankToUnknown(source));
    entry.setPreviousPlan(previousPlan);
    entry.setNewPlan(newPlan);
    entry.setPreviousStatus(previousStatus);
    entry.setNewStatus(newStatus);
    entry.setReason(reason);
    entry.setMetadata(toJson(metadata));
    auditLogRepository.save(entry);
  }

  private String toJson(Map<String, ?> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Audit metadata must be JSON serializable", ex);
    }
  }

  private static String blankToUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
