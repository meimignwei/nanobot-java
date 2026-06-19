package com.nanobot.providers;

import java.util.Map;

/**
 * Mirrors Python model_overrides tuple entry: (model_name, dict_of_overrides).
 */
public record ModelOverride(String model, Map<String, Object> overrides) {
}
