package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.ScriptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptRepository extends JpaRepository<ScriptEntity, Long> {
}
