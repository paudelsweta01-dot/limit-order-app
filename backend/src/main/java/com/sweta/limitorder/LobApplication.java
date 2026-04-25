package com.sweta.limitorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LobApplication {

    public static void main(String[] args) {
        SpringApplication.run(LobApplication.class, args);
    }
}
