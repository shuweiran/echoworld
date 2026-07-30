package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.WorldSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorldSnapshotRepository extends JpaRepository<WorldSnapshotEntity, Long> {
    List<WorldSnapshotEntity> findTop10ByOrderByCreatedAtDesc();
}
