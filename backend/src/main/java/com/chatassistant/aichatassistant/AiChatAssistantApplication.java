package com.chatassistant.aichatassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AiChatAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiChatAssistantApplication.class, args);
    }
}
