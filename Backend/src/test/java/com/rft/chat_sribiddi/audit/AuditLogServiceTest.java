package com.rft.chat_sribiddi.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rft.chat_sribiddi.auth.Plan;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

  @Mock private AuditLogRepository auditLogRepository;

  private AuditLogService service;

  @BeforeEach
  void setUp() {
    service = new AuditLogService(auditLogRepository, new ObjectMapper());
  }

  @Test
  void recordPlanStateChange_persistsPlanAuditEntryWithMetadata() {
    service.recordPlanStateChange(
        42L,
        Plan.FREE,
        Plan.PREMIUM,
        null,
        "active",
        "stripe_webhook",
        "customer.subscription.updated",
        Map.of("event_id", "evt_123"));

    ArgumentCaptor<AuditLogEntity> entryCap = ArgumentCaptor.forClass(AuditLogEntity.class);
    verify(auditLogRepository).save(entryCap.capture());
    AuditLogEntity entry = entryCap.getValue();
    assertThat(entry.getUserId()).isEqualTo(42L);
    assertThat(entry.getEventType()).isEqualTo(AuditLogService.PLAN_STATE_CHANGE);
    assertThat(entry.getSource()).isEqualTo("stripe_webhook");
    assertThat(entry.getPreviousPlan()).isEqualTo(Plan.FREE);
    assertThat(entry.getNewPlan()).isEqualTo(Plan.PREMIUM);
    assertThat(entry.getPreviousStatus()).isNull();
    assertThat(entry.getNewStatus()).isEqualTo("active");
    assertThat(entry.getReason()).isEqualTo("customer.subscription.updated");
    assertThat(entry.getMetadata()).contains("\"event_id\":\"evt_123\"");
  }

  @Test
  void recordPlanStateChange_skipsUnchangedState() {
    service.recordPlanStateChange(
        42L,
        Plan.PREMIUM,
        Plan.PREMIUM,
        "active",
        "active",
        "stripe_webhook",
        "customer.subscription.updated",
        Map.of("event_id", "evt_123"));

    verify(auditLogRepository, never()).save(any());
  }
}
