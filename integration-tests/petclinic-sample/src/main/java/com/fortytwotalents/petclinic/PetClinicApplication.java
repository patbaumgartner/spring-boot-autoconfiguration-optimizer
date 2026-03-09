package com.fortytwotalents.petclinic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * PetClinic-like sample application for the Spring Boot Autoconfiguration Optimizer.
 *
 * <p>This application mirrors the technology stack of Spring PetClinic:
 * <ul>
 *   <li>Spring MVC with Thymeleaf templates</li>
 *   <li>Spring Data JPA with H2 database</li>
 *   <li>Spring Boot Actuator</li>
 *   <li>Spring Cache</li>
 *   <li>Bean Validation</li>
 * </ul>
 *
 * <p>It is used as the integration test and benchmark target for the optimizer.
 * See the README for usage instructions.
 */
@SpringBootApplication
@EnableCaching
public class PetClinicApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetClinicApplication.class, args);
    }
}
