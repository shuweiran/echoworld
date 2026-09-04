package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.CharacterVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CharacterVersionRepository extends JpaRepository<CharacterVersionEntity, Long> {
    List<CharacterVersionEntity> findByCharacterNameOrderByVersionNoAsc(String characterName);

    Optional<CharacterVersionEntity> findFirstByCharacterNameOrderByVersionNoDesc(String characterName);

    long countByCharacterName(String characterName);
}
