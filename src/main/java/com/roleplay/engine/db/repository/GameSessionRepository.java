package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.GameSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameSessionRepository extends JpaRepository<GameSessionEntity, String> {
    List<GameSessionEntity> findBySessionTypeOrderByUpdatedAtDesc(String sessionType);
}
