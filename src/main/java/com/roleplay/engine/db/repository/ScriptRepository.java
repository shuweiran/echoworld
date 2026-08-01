package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.ScriptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScriptRepository extends JpaRepository<ScriptEntity, Long> {

    /** C3: 对局快照查询 —— name 前缀为「对局快照:<sessionId>」时返回该对局全部快照（新→旧），取首个即最新。 */
    List<ScriptEntity> findByNameStartingWithOrderByIdDesc(String prefix);
}
