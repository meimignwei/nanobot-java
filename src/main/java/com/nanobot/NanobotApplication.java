package com.nanobot;

import com.nanobot.config.NanobotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NanobotProperties.class)
public class NanobotApplication {

    public static void main(String[] args) {
        SpringApplication.run(NanobotApplication.class, args);
    }
}
