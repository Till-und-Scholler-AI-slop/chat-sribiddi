package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.ChatEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatRepository extends JpaRepository<ChatEntity, UUID> {}
