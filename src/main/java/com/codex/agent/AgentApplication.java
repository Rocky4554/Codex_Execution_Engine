package com.codex.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentApplication {

    public static void main(String[] args) {
        // Force UTC like the parent backend to avoid timestamp surprises in logs
        System.setProperty("user.timezone", "UTC");
        SpringApplication.run(AgentApplication.class, args);
    }
}
