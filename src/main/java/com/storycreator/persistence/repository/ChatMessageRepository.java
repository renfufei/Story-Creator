package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<ChatMessageEntity> findTop40BySessionIdOrderByCreatedAtDesc(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
