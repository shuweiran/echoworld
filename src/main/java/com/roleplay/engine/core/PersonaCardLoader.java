package com.roleplay.engine.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * P-0810-10：五层 persona 卡加载器（静态工具）。
 *
 * <p>加载 {@code classpath:persona/*.json} 默认角色卡（小铃/凯尔/露娜等），按角色名（或卡内
 * {@code id} 别名）索引；把卡合并进 {@link Persona}——五层数据进 {@code layers}（内部设定），
 * appearance/summary 进表层字段，personaDesc/voice/background 仅在角色原本为空/占位时回填
 * （用户显式传入的内容绝不覆盖）。
 *
 * <p>设计说明：静态懒加载（首次访问时读 resources），不依赖 Spring 注入，任何 Persona 构建点
 * 一行接入即可；测试可用 {@link #resetForTests()} 清缓存。
 *
 * <p>P-0811-D：五层卡外部持久化——新增外部卡目录（{@code roleplay.game.persona-cards-dir}，
 * 默认 {@code ./data/persona}）。加载时 classpath 默认卡 + 外部目录合并（外部目录优先），
 * 目录不存在/不可读 → 静默跳过零破坏；既有 resources/persona/ 三张默认卡逻辑不动（向后兼容）。
 * 外部目录由 {@link #setExternalCardsDir(Path)} 设置（CharacterController @PostConstruct 注入配置值）。
 */
public final class PersonaCardLoader {

    private static final Logger log = LoggerFactory.getLogger(PersonaCardLoader.class);

    /** P-0811-D：外部 persona 卡目录（写入/扫描目标；null=未启用，仅 classpath 默认卡）。 */
    private static volatile Path EXTERNAL_DIR;

    /** 默认卡文件清单（resources/persona/ 下，与 docs/persona-五层卡-格式.md 契约一致）。 */
    public static final List<String> CARD_FILES = List.of(
            "xiaoling.json",  // 小铃（heroine）
            "kyle.json",      // 凯尔（knight）
            "luna.json"       // 露娜（luna）
    );

    /** 卡内五层相关键（导入校验/加载时只取这些键进 Persona.layers）。 */
    public static final List<String> LAYER_KEYS = List.of(
            "layer0", "layer1", "layer2", "layer3", "layer4", "contrast", "humanDetails"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile Map<String, Map<String, Object>> CARDS;

    private PersonaCardLoader() {}

    /** 按角色名取默认卡（无则 null）。 */
    public static Map<String, Object> cardFor(String name) {
        ensureLoaded();
        if (name == null) return null;
        return CARDS.get(name);
    }

    /** 是否注册了该角色名的默认卡。 */
    public static boolean hasCard(String name) {
        return cardFor(name) != null;
    }

    /**
     * 给 Persona 挂五层卡（默认卡）：已有 layer 数据或无名卡 → no-op。
     * 用户显式传入的 personaDesc/voice/background 保留（只在空/占位时回填）。
     */
    public static void attachDefault(Persona p) {
        if (p == null || p.hasLayers()) return;
        attach(p, null);
    }

    /**
     * 给 Persona 挂卡：优先导入卡（POST /api/characters/{name}/persona 的产物），
     * 无导入卡时回退默认资源卡；已挂 layer 数据 → no-op（不覆盖）。
     */
    public static void attach(Persona p, Map<String, Object> importedCard) {
        if (p == null || p.hasLayers()) return;
        Map<String, Object> card = importedCard != null ? importedCard : cardFor(p.getName());
        if (card == null) return;
        applyCard(p, card);
    }

    /** 把一张卡（导入或默认）合并进 Persona。 */
    public static void applyCard(Persona p, Map<String, Object> card) {
        if (p == null || card == null) return;
        Map<String, Object> layers = new LinkedHashMap<>();
        for (String k : LAYER_KEYS) {
            if (card.containsKey(k) && card.get(k) != null) {
                layers.put(k, card.get(k));
            }
        }
        if (!layers.isEmpty()) {
            p.setLayers(layers);
        }
        if (p.getAppearance().isEmpty() && card.get("appearance") != null) {
            p.setAppearance(String.valueOf(card.get("appearance")));
        }
        if (p.getSummary().isEmpty() && card.get("summary") != null) {
            p.setSummary(String.valueOf(card.get("summary")));
        }
        // 表层回填：仅在原本为空或占位「name，一个角色」时（用户显式内容不覆盖）
        if (card.get("personaDesc") != null
                && (p.getPersonaDesc().isEmpty() || p.getPersonaDesc().equals(p.getName() + "，一个角色"))) {
            p.setPersonaDesc(String.valueOf(card.get("personaDesc")));
        }
        if (p.getVoice().isEmpty() && card.get("voice") != null) {
            p.setVoice(String.valueOf(card.get("voice")));
        }
        if (p.getBackground().isEmpty() && card.get("background") != null) {
            p.setBackground(String.valueOf(card.get("background")));
        }
    }

    private static void ensureLoaded() {
        if (CARDS != null) return;
        synchronized (PersonaCardLoader.class) {
            if (CARDS != null) return;
            Map<String, Map<String, Object>> cards = new LinkedHashMap<>();
            // 1) classpath 默认卡（既有逻辑不动）
            ClassLoader cl = PersonaCardLoader.class.getClassLoader();
            for (String file : CARD_FILES) {
                try (InputStream is = cl.getResourceAsStream("persona/" + file)) {
                    if (is == null) {
                        log.warn("PersonaCardLoader: 默认卡缺失 resources/persona/{}", file);
                        continue;
                    }
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    Map<String, Object> card = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
                    String nm = card.get("name") != null ? String.valueOf(card.get("name")) : file.replace(".json", "");
                    cards.put(nm, card);
                    if (card.get("id") != null) {
                        cards.put(String.valueOf(card.get("id")), card); // id 别名（heroine/knight/luna）
                    }
                    log.info("PersonaCardLoader: 已加载默认卡 persona/{} → 角色「{}」", file, nm);
                } catch (Exception e) {
                    log.warn("PersonaCardLoader: 加载 persona/{} 失败: {}", file, e.getMessage());
                }
            }
            // 2) 外部目录合并（P-0811-D）：目录不存在/不可读 → 静默跳过零破坏；同名卡外部优先（后 put 覆盖）
            Path dir = EXTERNAL_DIR;
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(p -> p.getFileName() != null
                                    && p.getFileName().toString().endsWith(".json"))
                         .sorted()
                         .forEach(p -> loadExternalCard(cards, p));
                } catch (IOException e) {
                    log.warn("PersonaCardLoader: 扫描外部卡目录 {} 失败（跳过）: {}", dir, e.getMessage());
                }
            }
            CARDS = cards;
        }
    }

    /** P-0811-D：解析单张外部卡并合并进索引（外部优先：同名覆盖 classpath 卡）。 */
    private static void loadExternalCard(Map<String, Map<String, Object>> cards, Path file) {
        try {
            Map<String, Object> card = MAPPER.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {});
            String nm = card.get("name") != null ? String.valueOf(card.get("name"))
                    : file.getFileName().toString().replace(".json", "");
            cards.put(nm, card);
            if (card.get("id") != null) {
                cards.put(String.valueOf(card.get("id")), card); // id 别名同 classpath 规则
            }
            log.info("PersonaCardLoader: 已加载外部卡 {} → 角色「{}」", file, nm);
        } catch (Exception e) {
            log.warn("PersonaCardLoader: 解析外部卡 {} 失败（跳过）: {}", file, e.getMessage());
        }
    }

    /**
     * P-0811-D：设置外部 persona 卡目录（配置 {@code roleplay.game.persona-cards-dir}）。
     * 目录变化 → 清缓存，下次访问重扫（classpath + 外部合并，外部优先）。
     */
    public static void setExternalCardsDir(Path dir) {
        synchronized (PersonaCardLoader.class) {
            EXTERNAL_DIR = dir;
            CARDS = null;
        }
    }

    /** 测试钩子：清缓存 + 清外部目录（测试隔离用）。 */
    public static void resetForTests() {
        synchronized (PersonaCardLoader.class) {
            CARDS = null;
            EXTERNAL_DIR = null;
        }
    }
}
