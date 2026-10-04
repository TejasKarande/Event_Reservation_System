package com.ticketbooking.system.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JwtTokenGenerator implements CommandLineRunner {

    private final JwtService jwtService;

    public JwtTokenGenerator(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void run(String... args) {

        String userToken =
                jwtService.generateToken("1001", List.of("USER"));

        String adminToken =
                jwtService.generateToken("1", List.of("ADMIN"));

        System.out.println("USER TOKEN:");
        System.out.println(userToken);

        System.out.println("\nADMIN TOKEN:");
        System.out.println(adminToken);
    }
}