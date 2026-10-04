package com.marcusrdrigues.noxguard.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Example Books chat: the noxguard guards wired into a Spring Boot WebFlux app. */
@SpringBootApplication
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
