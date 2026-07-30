package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.ConversationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationLogRepository extends JpaRepository<ConversationLogEntity, Long> {
    List<ConversationLogEntity> findByGroupIdOrderByCreatedAtDesc(String groupId);
}
