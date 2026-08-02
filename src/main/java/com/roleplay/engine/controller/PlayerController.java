package com.roleplay.engine.controller;

import com.roleplay.engine.service.PlayerIdentityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 玩家身份端点（改造方案《玩家角色改名与 AI 识别》Phase 3：局中改名端点·同步式）。
 *
 * <pre>{@code
 * POST /api/player/rename
 * Body（推荐）: { "player_id": "<UUID>", "new_name": "新名字" }
 * Body（兼容，无 player_id 时）: { "old_name": "旧名字", "new_name": "新名字" }
 * }</pre>
 *
 * <p>成功 {@code 200 {new_name, old_name, synced_sessions:[...], collision:false}}；
 * 撞名 {@code 409 {error}}；鉴权失败 {@code 403}；部分同步失败 {@code 500 {error, rolled_back:true}}。
 *
 * <p>与既有 {@code PUT /api/characters/{name}} 的关系：原端点保留（前端未升级仍可改名但不做局中同步）；
 * 升级后的角色库改名弹窗改调本端点（跨服务编排：角色库改名 + Router/2D/Werewolf/Script 四处运行态同步）。
 */
@RestController
@RequestMapping("/api/player")
public class PlayerController {

    private final PlayerIdentityService identityService;

    public PlayerController(PlayerIdentityService identityService) {
        this.identityService = identityService;
    }

    @PostMapping("/rename")
    public ResponseEntity<Map<String, Object>> rename(@RequestBody Map<String, Object> body) {
        String playerId = body.get("player_id") != null ? String.valueOf(body.get("player_id")) : "";
        String oldName = body.get("old_name") != null ? String.valueOf(body.get("old_name")) : "";
        String newName = body.get("new_name") != null ? String.valueOf(body.get("new_name")) : "";
        Map<String, Object> result = identityService.renamePlayerCharacter(playerId, oldName, newName);
        int status = result.containsKey("status") ? ((Number) result.get("status")).intValue() : 200;
        return ResponseEntity.status(status).body(result);
    }
}
