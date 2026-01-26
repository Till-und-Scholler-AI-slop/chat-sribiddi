package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.MessageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

  void deleteByChatId(UUID chatId);

  List<MessageEntity> findByChatIdOrderByCreatedAtDesc(UUID chatId, Pageable pageable);
}
