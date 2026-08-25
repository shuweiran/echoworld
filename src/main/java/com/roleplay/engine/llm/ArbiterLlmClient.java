package com.roleplay.engine.llm;

import com.roleplay.engine.config.AppConfig;
import org.springframework.stereotype.Service;

/**
 * 主控专用 LLM：调度、叙事整合、地图以及角色/场景生成使用本客户端。
 * 角色的逐轮台词继续由主 {@link LLMClient} 生成，避免两种职责争抢同一配置。
 */
@Service
public class ArbiterLlmClient extends LLMClient {

    public ArbiterLlmClient(AppConfig appConfig) {
        super(appConfig, Provider.ARBITER);
    }
}
