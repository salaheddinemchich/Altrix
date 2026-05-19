package com.altrix.project;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: required by WebhookDeliveryPurgeScheduler (#91) which
// removes audit rows older than 30 days every night at 02:00.
@EnableScheduling
@SpringBootApplication
public class ProjectApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProjectApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(){
        return args -> System.out.println("############################# Project Application Started #############################");
    }
}
