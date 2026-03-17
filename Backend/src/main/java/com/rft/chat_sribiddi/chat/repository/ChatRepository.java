package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.ChatEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ChatRepository extends JpaRepository<ChatEntity, UUID> {
  Optional<ChatEntity> findByChatIdAndUserId(UUID chatId, String userId);

  List<ChatEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

  @Modifying
  @Transactional
  @Query("UPDATE ChatEntity c SET c.title = :title WHERE c.chatId = :chatId AND (c.title IS NULL OR TRIM(c.title) = '')")
  int setTitleIfBlank(@Param("chatId") UUID chatId, @Param("title") String title);

  @Modifying
  @Query("DELETE FROM ChatEntity c WHERE c.userId = :userId")
  void deleteByUserId(@Param("userId") String userId);
}
