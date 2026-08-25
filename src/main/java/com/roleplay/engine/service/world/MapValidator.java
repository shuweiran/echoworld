package com.roleplay.engine.service.world;

import java.util.Map;

/** 可注入的地图契约验证函数。 */
@FunctionalInterface
public interface MapValidator {
    MapValidationResult validate(Map<String, Object> mapData) throws Exception;
}
