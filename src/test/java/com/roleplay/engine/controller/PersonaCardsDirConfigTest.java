package com.roleplay.engine.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-0811-D：五层卡持久化配置键（roleplay.game.persona-cards-dir）绑定验证。
 *
 * <p>① test profile 下 @SpringBootTest 真实上下文绑定（值 = application-test.yml 的 target/persona-test-cards，
 * 指向构建目录避免污染 ./data/persona）；② 真实 CharacterController bean 装配成功（@PostConstruct 注册
 * 外部卡目录到 PersonaCardLoader，路径可配对齐 D-004 勿 hardcode 纪律）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class PersonaCardsDirConfigTest {

    @Autowired
    private Environment env;

    @Autowired
    private CharacterController characterController;

    @Test
    @DisplayName("配置键绑定：test yml 的 roleplay.game.persona-cards-dir 被 Spring 读取")
    void personaCardsDirKeyBoundInTestProfile() {
        assertEquals("target/persona-test-cards",
                env.getProperty("roleplay.game.persona-cards-dir"),
                "application-test.yml 必须含 roleplay.game.persona-cards-dir 键且被绑定");
        assertNotNull(characterController, "真实 CharacterController bean 装配成功");
    }

    @Test
    @DisplayName("主 yml 含配置键（生产默认 ./data/persona）")
    void mainYmlHasKey() throws Exception {
        String main = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertTrue(main.contains("persona-cards-dir"), "主 yml 必须含 persona-cards-dir 键");
        assertTrue(main.contains("./data/persona"), "主 yml 默认值 ./data/persona");
    }
}
