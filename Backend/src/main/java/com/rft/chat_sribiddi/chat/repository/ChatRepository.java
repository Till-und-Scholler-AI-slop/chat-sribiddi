package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.ChatEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatRepository extends JpaRepository<ChatEntity, UUID> {
  Optional<ChatEntity> findByChatIdAndUserId(UUID chatId, String userId);

  List<ChatEntity> findByUserIdOrderByCreatedAtDesc(String userId);

  @Modifying
  @Query("DELETE FROM ChatEntity c WHERE c.userId = :userId")
  void deleteByUserId(@Param("userId") String userId);
}
