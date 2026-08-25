package com.roleplay.engine.service.world;

/** 地图契约验证结果。 */
public record MapValidationResult(boolean valid, String error) {

    public static MapValidationResult success() {
        return new MapValidationResult(true, null);
    }

    public static MapValidationResult invalid(String error) {
        String message = error == null || error.isBlank() ? "map validation failed" : error;
        return new MapValidationResult(false, message);
    }
}
