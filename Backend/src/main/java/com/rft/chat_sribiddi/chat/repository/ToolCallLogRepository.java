package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.ToolCallLogEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ToolCallLogRepository extends JpaRepository<ToolCallLogEntity, Long> {

  List<ToolCallLogEntity> findByMessageIdOrderByStartedAtAsc(Long messageId);

  @Query(
      """
      SELECT DISTINCT t.messageId
      FROM ToolCallLogEntity t
      WHERE t.messageId IN :messageIds
      """)
  List<Long> findMessageIdsWithToolCalls(@Param("messageIds") Collection<Long> messageIds);
}
