package com.roleplay.engine.service.world;

import java.util.Map;

/** 可注入的实际地图生成函数。 */
@FunctionalInterface
public interface MapGenerator {
    Map<String, Object> generate(String sessionId, MapGenerationRequest request) throws Exception;
}
