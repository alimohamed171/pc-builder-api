package com.pcbuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class PcBuilderApplication {
    public static void main(String[] args) {
        SpringApplication.run(PcBuilderApplication.class, args);
    }
}
