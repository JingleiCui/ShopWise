package com.shopwise;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
class HealthEndpoints {
    private final JdbcTemplate db;
    HealthEndpoints(JdbcTemplate db) { this.db=db; }
    @GetMapping("/api/health/live")
    Map<String,String> liveness() { return Map.of("status","UP"); }

    @GetMapping("/api/health/ready")
    ResponseEntity<Map<String,String>> readiness() {
        try {
            db.queryForObject("select 1",Integer.class);
            return ResponseEntity.ok(Map.of("status","UP"));
        } catch (org.springframework.dao.DataAccessException unavailable) {
            return ResponseEntity.status(503).body(Map.of("status","DOWN"));
        }
    }
}
