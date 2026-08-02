package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.CharacterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CharacterRepository extends JpaRepository<CharacterEntity, Long> {
    Optional<CharacterEntity> findByName(String name);
    boolean existsByName(String name);
    void deleteByName(String name);

    /** 改造方案 Phase 1：player_id 绑定反查（解析器 PlayerIdentityService 数据源） */
    Optional<CharacterEntity> findByPlayerId(String playerId);
}
