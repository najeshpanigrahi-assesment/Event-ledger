package com.eventledger.gateway.controller;

import com.eventledger.gateway.service.AccountServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final AccountServiceClient accountServiceClient;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "event-gateway");
        response.put("timestamp", Instant.now().toString());

        // DB check
        boolean dbOk = false;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbOk = true;
        } catch (Exception ex) {
            log.error("DB health check failed: {}", ex.getMessage());
        }

        boolean accountServiceOk = accountServiceClient.isHealthy();

        response.put("database", dbOk ? "UP" : "DOWN");
        response.put("accountService", accountServiceOk ? "UP" : "DOWN");
        response.put("status", dbOk ? "UP" : "DOWN");

        return ResponseEntity.ok(response);
    }
}
