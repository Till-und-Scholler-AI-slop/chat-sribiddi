package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.ChatToolEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatToolRepository extends JpaRepository<ChatToolEntity, Long> {

  List<ChatToolEntity> findByChatId(UUID chatId);

  Optional<ChatToolEntity> findByChatIdAndToolName(UUID chatId, String toolName);

  void deleteByChatIdAndToolName(UUID chatId, String toolName);

  boolean existsByChatIdAndToolName(UUID chatId, String toolName);
}
