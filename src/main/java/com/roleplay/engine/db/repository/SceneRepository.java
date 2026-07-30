package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.SceneEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SceneRepository extends JpaRepository<SceneEntity, String> {
    Optional<SceneEntity> findById(String id);
}
