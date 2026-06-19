package com.nanobot.providers;

/**
 * Mirrors Python env_extras tuple entry: (env_var_name, value_template).
 * Value template may contain {api_key} or {api_base} placeholders resolved at runtime.
 */
public record EnvExtra(String key, String valueTemplate) {
}
