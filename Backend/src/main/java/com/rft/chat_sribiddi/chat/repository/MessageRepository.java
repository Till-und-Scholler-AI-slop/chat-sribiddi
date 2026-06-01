package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.MessageEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

  void deleteByChatId(UUID chatId);

  @Modifying
  @Query("DELETE FROM MessageEntity m WHERE m.chatId IN :chatIds")
  void deleteAllByChatIdIn(@Param("chatIds") Collection<UUID> chatIds);

  @Modifying
  @Query("DELETE FROM MessageEntity m WHERE EXISTS (SELECT 1 FROM ChatEntity c WHERE c.chatId = m.chatId AND c.userId = :userId)")
  void deleteAllByUserId(@Param("userId") String userId);

  List<MessageEntity> findByChatIdOrderByCreatedAtDesc(UUID chatId, Pageable pageable);

  @Query("SELECT m FROM MessageEntity m WHERE m.chatId = :chatId AND (:beforeMessageId IS NULL OR m.id < :beforeMessageId) ORDER BY m.createdAt DESC")
  List<MessageEntity> findContextMessagesBefore(
      @Param("chatId") UUID chatId,
      @Param("beforeMessageId") Long beforeMessageId,
      Pageable pageable);

  @Query("SELECT COUNT(m) FROM MessageEntity m WHERE m.chatId = :chatId AND (:beforeMessageId IS NULL OR m.id < :beforeMessageId)")
  long countContextMessagesBefore(
      @Param("chatId") UUID chatId, @Param("beforeMessageId") Long beforeMessageId);

  List<MessageEntity> findByChatIdOrderByCreatedAtAsc(UUID chatId);

  Optional<MessageEntity> findByIdAndChatId(Long id, UUID chatId);

  @Modifying
  @Query("DELETE FROM MessageEntity m WHERE m.chatId = :chatId AND m.id > :afterMessageId")
  void deleteByIdGreaterThanAndChatId(@Param("chatId") UUID chatId, @Param("afterMessageId") Long afterMessageId);
}
