package com.nanobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 入口。对标 Python {@code nanobot/__main__.py} 中的 {@code app()} 调用。
 * 源码文件：nanobot/__main__.py
 */
@SpringBootApplication
public class NanobotApplication {

    public static void main(String[] args) {
        SpringApplication.run(NanobotApplication.class, args);
    }
}
