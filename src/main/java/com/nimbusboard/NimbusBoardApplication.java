package com.nimbusboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NimbusBoardApplication {

    public static void main(String[] args) {
        SpringApplication.run(NimbusBoardApplication.class, args);
    }
}
