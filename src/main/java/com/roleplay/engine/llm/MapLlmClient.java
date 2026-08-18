package com.roleplay.engine.llm;

import com.roleplay.engine.config.AppConfig;
import org.springframework.stereotype.Service;

/**
 * 地图生成专用 LLM 客户端（P-0818-B，小米 MiMo）——只用于地图/结构生成路径
 * （ScriptMapService LLM 地图、StructureLlmBlueprint custom 结构蓝图），
 * 端点/模型/key 读 {@code roleplay.map-llm.*}；对话/剧本等主链路仍走 {@link LLMClient}（DeepSeek）。
 */
@Service
public class MapLlmClient extends LLMClient {

    public MapLlmClient(AppConfig appConfig) {
        super(appConfig, true);
    }
}
