package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Registra alertas mesmo quando nenhum usuário está com o dashboard aberto. */
@Component
public class AlertaMonitor {
    private final JdbcTemplate db;
    private final AlertaService alertas;
    public AlertaMonitor(JdbcTemplate db, AlertaService alertas) { this.db = db; this.alertas = alertas; }
    @Scheduled(initialDelay = 5000, fixedDelay = 60000)
    public void verificar() {
        try {
            for (int empresa : db.queryForList("SELECT DISTINCT empresa_id FROM produtos WHERE ativo=true", Integer.class)) {
                try { alertas.atualizar(empresa); }
                catch (Exception erro) {
                    org.slf4j.LoggerFactory.getLogger(AlertaMonitor.class).warn(
                            "Não foi possível atualizar alertas da empresa {} ({})", empresa, erro.getClass().getSimpleName());
                }
            }
        } catch (Exception erro) {
            org.slf4j.LoggerFactory.getLogger(AlertaMonitor.class).warn(
                    "Monitor de alertas sem acesso ao banco ({})", erro.getClass().getSimpleName());
        }
    }
}
