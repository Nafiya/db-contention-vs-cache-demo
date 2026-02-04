package com.limitcache;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LimitCacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(LimitCacheApplication.class, args);
    }
}
