package com.roleplay.engine.service;

import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.controller.ScriptController;
import com.roleplay.engine.controller.WerewolfController;
import com.roleplay.engine.db.entity.CharacterEntity;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.simulation.SimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 玩家身份模型服务（改造方案《玩家角色改名与 AI 识别》Phase 1：身份字段落地；Phase 3：局中改名编排）。
 *
 * <p>player_id = 客户端首次生成（crypto.randomUUID）并 localStorage 持久化的玩家唯一 UUID，
 * 角色库 {@code CharacterEntity.playerId} 列承载绑定（一个角色最多绑定一个玩家）。
 * 对齐既有 roleKey 令牌先例（DECISION_LOG D-017/D-028/D-029）。
 *
 * <p>解析器为纯 DB 查询、零缓存：改名后无需任何缓存同步，解析永远得到最新角色名
 * ——「解析式」判定支柱（方案 §3.1），即使某次局中同步失败，主控判定仍按
 * player_id 解析到新名，不会静默 AI 化。
 *
 * <p>Phase 2 起四处判定链路（RouterService:233 / SimulationService:200 / WerewolfController:81 /
 * ScriptController init）统一调用：resolve 命中 → 用解析出的当前角色名；
 * 未命中（player_id 缺省/未绑定）→ 回退 player_name 字符串（现状逻辑，零行为变化）。
 *
 * <p>Phase 3（局中改名端点，同步式）：{@link #renamePlayerCharacter} 编排 —— 撞名校验② →
 * 角色库改名 → 四服务同步（Router/2D/Werewolf/Script rename 方法）→ 失败回滚 → 返回 synced_sessions。
 * 协作方经 @Lazy 注入（RouterService/SimulationService/WerewolfController/ScriptController 均依赖本服务，
 * 直构循环依赖用 @Lazy 代理断开；单参构造保留供既有测试直接构造，协作方为 null 时编排方法 null 守卫跳过）。
 */
@Service
public class PlayerIdentityService {

    private static final Logger log = LoggerFactory.getLogger(PlayerIdentityService.class);

    private final CharacterRepository characterRepo;

    // ── Phase 3 协作方（@Lazy 断开直构循环；单参构造路径为 null → 编排方法 null 守卫）──
    private final DatabaseService databaseService;
    private final CharacterController characterController;
    private final RouterService routerService;
    private final SimulationService simulationService;
    private final WerewolfService werewolfService;
    private final ScriptGameService scriptGameService;
    private final WerewolfController werewolfController;
    private final ScriptController scriptController;

    /** 既有测试直构路径：仅仓库（resolve 双方法可用；编排方法协作方为 null 时跳过对应同步）。 */
    public PlayerIdentityService(CharacterRepository characterRepo) {
        this(characterRepo, null, null, null, null, null, null, null, null);
    }

    /** Spring 注入路径：@Lazy 断开与依赖本服务的组件（RouterService/SimulationService/两 controller）的构造循环。 */
    @Autowired
    public PlayerIdentityService(CharacterRepository characterRepo,
                                 @Lazy DatabaseService databaseService,
                                 @Lazy CharacterController characterController,
                                 @Lazy RouterService routerService,
                                 @Lazy SimulationService simulationService,
                                 @Lazy WerewolfService werewolfService,
                                 @Lazy ScriptGameService scriptGameService,
                                 @Lazy WerewolfController werewolfController,
                                 @Lazy ScriptController scriptController) {
        this.characterRepo = characterRepo;
        this.databaseService = databaseService;
        this.characterController = characterController;
        this.routerService = routerService;
        this.simulationService = simulationService;
        this.werewolfService = werewolfService;
        this.scriptGameService = scriptGameService;
        this.werewolfController = werewolfController;
        this.scriptController = scriptController;
    }

    /**
     * player_id → 当前绑定角色名。查无绑定或入参空白 → empty（调用方回退 player_name 字符串）。
     */
    public Optional<String> resolveCharacterName(String playerId) {
        if (playerId == null || playerId.isBlank()) return Optional.empty();
        return characterRepo.findByPlayerId(playerId).map(CharacterEntity::getName);
    }

    /**
     * 角色名 → 绑定的 player_id（反查）。角色不存在/未绑定 → empty。
     */
    public Optional<String> resolvePlayerId(String characterName) {
        if (characterName == null || characterName.isBlank()) return Optional.empty();
        return characterRepo.findByName(characterName).map(CharacterEntity::getPlayerId);
    }

    // ═══════════════════════════════════════════════════════════
    //  Phase 3: 局中改名编排（同步式，方案 §4.1 处理流程）
    // ═══════════════════════════════════════════════════════════

    /**
     * 局中改名编排（POST /api/player/rename）：
     * <ol>
     *   <li>定位：player_id → CharacterEntity（或 old_name → 实体）；鉴权（带 player_id 必须命中绑定）；</li>
     *   <li>撞名校验②：库内同名（排除自身）→ 409；活跃会话内同名角色（Router/2D/Wolf/Script）→ 409；</li>
     *   <li>角色库改名：CharacterController 内存列表改名 + DatabaseService 删旧建新（playerId 绑定随新行保留）；</li>
     *   <li>收集活跃会话：Router（agents 含旧名）、2D（world 含旧名）、Wolf/Script（sessionsOfPlayer 含旧名）；</li>
     *   <li>逐个同步：router.renameAgent / simulation.renamePlayerCharacter / werewolf.renamePlayer / script.renamePlayer
     *       + 两 controller 的 playerSessions 键换名；</li>
     *   <li>任一失败 → 回滚已改项（角色库改回旧名 + 已同步会话改回旧名），返回 500 {rolled_back: true}；</li>
     *   <li>返回 synced_sessions 清单。</li>
     * </ol>
     *
     * @return 成功 {@code {new_name, old_name, synced_sessions, collision:false}}；
     *         409 {@code {error, status:409}}；403 {@code {error, status:403}}；
     *         500 {@code {error, status:500, rolled_back:true}}
     */
    public Map<String, Object> renamePlayerCharacter(String playerId, String oldName, String newName) {
        // ── 1. 定位 + 鉴权 ──
        CharacterEntity entity;
        String resolvedOld;
        if (playerId != null && !playerId.isBlank()) {
            Optional<CharacterEntity> e = characterRepo.findByPlayerId(playerId);
            if (e.isEmpty()) return error(403, "未绑定玩家角色");
            entity = e.get();
            resolvedOld = entity.getName();
        } else if (oldName != null && !oldName.isBlank()) {
            // 兼容路径（无 player_id 用 old_name）：角色库内按名定位
            Optional<CharacterEntity> e = characterRepo.findByName(oldName);
            if (e.isEmpty()) return error(404, "角色不存在: " + oldName);
            entity = e.get();
            // 该名已被其他玩家绑定 → 必须走 player_id（防冒充他人角色，方案 §4.1 鉴权）
            if (entity.getPlayerId() != null && !entity.getPlayerId().isBlank()) {
                return error(403, "该角色已绑定玩家，请使用 player_id 改名");
            }
            resolvedOld = oldName;
        } else {
            return error(400, "缺少 player_id 或 old_name");
        }
        if (newName == null || newName.isBlank()) return error(400, "缺少 new_name");
        if (newName.equals(resolvedOld)) return error(400, "新旧名字相同");

        // ── 2. 撞名校验②：库内同名（排除自身）→ 409 ──
        if (characterRepo.findByName(newName).isPresent()) {
            return error(409, "角色名已存在: " + newName);
        }
        // 活跃会话内同名角色 → 409（防“改到与会话内 NPC 同名”造成身份混淆，方案 §5 层②）
        if (sessionHasName(newName)) {
            return error(409, "活跃会话内已存在同名角色: " + newName);
        }

        // ── 3. 角色库改名 + 4. 收集活跃会话 + 5. 逐个同步（任一失败 → 回滚）──
        List<String> synced = new ArrayList<>();
        try {
            // 角色库：内存列表改名 + DB 删旧建新（playerId 绑定随新行保留）
            if (characterController != null) {
                Map<String, Object> renamed = characterController.renameCharacterInMemory(resolvedOld, newName);
                if (renamed == null) return error(404, "角色不存在: " + resolvedOld);
            } else if (databaseService != null) {
                // 直构测试路径：DB 兜底（删旧建新，绑定随新行）
                databaseService.deleteCharacter(resolvedOld);
                databaseService.saveCharacter(newName, entity.getPersona(), entity.getVoice(),
                        entity.getBackground(), entity.getPlayerId());
            }

            // 一般模式（Router 单活动会话）：agents 含旧名 → 换键 + persona 改名 + 引用替换
            if (routerService != null && routerService.hasAgent(resolvedOld)) {
                routerService.renameAgent(resolvedOld, newName);
                synced.add("router");
            }
            // 2D 世界（单会话）：states/agents 换键 + 重新断言 playerControlled
            if (simulationService != null && simulationService.hasAgent(resolvedOld)) {
                simulationService.renamePlayerCharacter(resolvedOld, newName);
                synced.add("2d");
            }
            // 狼人杀（多局）：GameState 名字键全量迁移 + playerSessions 键换名
            if (werewolfService != null) {
                for (String sid : werewolfService.sessionsOfPlayer(resolvedOld)) {
                    werewolfService.renamePlayer(sid, resolvedOld, newName);
                    if (werewolfController != null) werewolfController.renamePlayerSessionKey(resolvedOld, newName);
                    synced.add("werewolf:" + sid);
                }
            }
            // 剧本杀（多局）：ScriptGame 名字键全量迁移 + playerSessions/playerIdBindings 键换名
            if (scriptGameService != null) {
                for (String sid : scriptGameService.sessionsOfPlayer(resolvedOld)) {
                    scriptGameService.renamePlayer(sid, resolvedOld, newName);
                    if (scriptController != null) scriptController.renamePlayerSessionKey(resolvedOld, newName);
                    synced.add("script:" + sid);
                }
            }
        } catch (Exception e) {
            // ── 6. 回滚已改项（角色库改回旧名 + 已同步会话改回旧名）──
            log.warn("Player rename {} → {} failed, rolling back: {}", resolvedOld, newName, e.getMessage());
            rollback(resolvedOld, newName, synced, entity);
            Map<String, Object> err = error(500, "改名同步失败，已回滚: " + e.getMessage());
            err.put("rolled_back", true);
            return err;
        }

        // ── 7. 返回 synced_sessions 清单 ──
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("new_name", newName);
        ok.put("old_name", resolvedOld);
        ok.put("synced_sessions", synced);
        ok.put("collision", false);
        log.info("Player character renamed: {} → {} (synced: {})", resolvedOld, newName, synced);
        return ok;
    }

    /** 撞名校验②辅助：活跃会话内是否已有同名角色（Router agents / 2D world / Wolf players / Script players）。 */
    private boolean sessionHasName(String name) {
        if (routerService != null && routerService.hasAgent(name)) return true;
        if (simulationService != null && simulationService.hasAgent(name)) return true;
        if (werewolfService != null && werewolfService.anyGameHasPlayer(name)) return true;
        if (scriptGameService != null && scriptGameService.anyGameHasPlayer(name)) return true;
        return false;
    }

    /**
     * 回滚：角色库改回旧名 + 已同步会话逐个改回旧名（同步的逆操作，复用各 rename 方法）。
     * 回滚本身失败仅告警（尽力回滚，不抛——主错误已返回）。
     */
    private void rollback(String oldName, String newName, List<String> synced, CharacterEntity entity) {
        try {
            if (characterController != null) {
                characterController.renameCharacterInMemory(newName, oldName);
            } else if (databaseService != null) {
                databaseService.deleteCharacter(newName);
                databaseService.saveCharacter(oldName, entity.getPersona(), entity.getVoice(),
                        entity.getBackground(), entity.getPlayerId());
            }
        } catch (Exception e) {
            log.warn("Rollback library rename failed ({} → {}): {}", newName, oldName, e.getMessage());
        }
        for (String s : synced) {
            try {
                if ("router".equals(s) && routerService != null) {
                    routerService.renameAgent(newName, oldName);
                } else if ("2d".equals(s) && simulationService != null) {
                    simulationService.renamePlayerCharacter(newName, oldName);
                } else if (s.startsWith("werewolf:") && werewolfService != null) {
                    werewolfService.renamePlayer(s.substring("werewolf:".length()), newName, oldName);
                    if (werewolfController != null) werewolfController.renamePlayerSessionKey(newName, oldName);
                } else if (s.startsWith("script:") && scriptGameService != null) {
                    scriptGameService.renamePlayer(s.substring("script:".length()), newName, oldName);
                    if (scriptController != null) scriptController.renamePlayerSessionKey(newName, oldName);
                }
            } catch (Exception e) {
                log.warn("Rollback session {} failed: {}", s, e.getMessage());
            }
        }
    }

    private static Map<String, Object> error(int status, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        m.put("detail", msg);
        m.put("status", status);
        return m;
    }
}
