package com.nanobot.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates cross-field configuration constraints on startup.
 * Mirrors Python @model_validator(mode="after") on Config.
 */
@Component
public class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    private final NanobotProperties props;

    public ConfigValidator(NanobotProperties props) {
        this.props = props;
    }

    @PostConstruct
    void validate() {
        props.validate();
        log.info("Configuration validated successfully");
    }
}
