package com.roleplay.engine.db.repository;

import com.roleplay.engine.db.entity.AssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * P-0804-C：素材库仓储（assets 表）。
 * 过滤查询按 assetType / characterName / sceneId 组合派生（全空 = findAll）。
 */
@Repository
public interface AssetRepository extends JpaRepository<AssetEntity, Long> {
    Optional<AssetEntity> findByName(String name);
    boolean existsByName(String name);
    void deleteByName(String name);

    List<AssetEntity> findByAssetType(String assetType);
    List<AssetEntity> findByCharacterName(String characterName);
    List<AssetEntity> findBySceneId(String sceneId);

    List<AssetEntity> findByAssetTypeAndCharacterName(String assetType, String characterName);
    List<AssetEntity> findByAssetTypeAndSceneId(String assetType, String sceneId);
    List<AssetEntity> findByCharacterNameAndSceneId(String characterName, String sceneId);
    List<AssetEntity> findByAssetTypeAndCharacterNameAndSceneId(String assetType, String characterName, String sceneId);
}
