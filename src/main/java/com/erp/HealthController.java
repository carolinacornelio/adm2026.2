package com.erp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint leve de verificação de integridade (health check) para balanceadores de carga.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final JdbcTemplate db;
    private final String instanceId;

    public HealthController(
            JdbcTemplate db,
            @Value("${HOSTNAME:${server.port:8080}}") String instanceId) {
        this.db = db;
        this.instanceId = instanceId;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        try {
            db.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "database", "UP",
                    "instance", instanceId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "database", "DOWN",
                    "instance", instanceId));
        }
    }
}
