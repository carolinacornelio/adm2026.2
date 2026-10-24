package com.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada atual do ERP: inicia o servidor HTTP, registra os
 * Controllers e configura o DataSource/JdbcTemplate.
 */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class ErpApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErpApplication.class, args);
    }
}
