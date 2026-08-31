package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, Long> {

    List<ChatSessionEntity> findAllByOrderByUpdatedAtDesc();
}
