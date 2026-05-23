package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.ChatDocumentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatDocumentRepository extends JpaRepository<ChatDocumentEntity, Long> {

  List<ChatDocumentEntity> findByChatIdAndUserIdOrderByCreatedAtDesc(UUID chatId, String userId);

  Optional<ChatDocumentEntity> findByIdAndChatIdAndUserId(Long id, UUID chatId, String userId);
}
