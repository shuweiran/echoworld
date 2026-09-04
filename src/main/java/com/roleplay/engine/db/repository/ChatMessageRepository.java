package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    Optional<ChatMessageEntity> findByMessageId(String messageId);

    List<ChatMessageEntity> findBySessionIdOrderByIdAsc(String sessionId);

    List<ChatMessageEntity> findBySessionIdAndStatusOrderByIdAsc(String sessionId, String status);

    long countBySessionId(String sessionId);
}
