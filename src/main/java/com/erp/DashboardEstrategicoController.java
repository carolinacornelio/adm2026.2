package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Indicadores estratégicos (funcionários, Squads, OKRs) vindos do PostgreSQL. Cada seção só é devolvida
 * se o usuário possui a permissão correspondente (users.view, squads.view, okrs.view); o proprietário vê tudo.
 */
@RestController
public class DashboardEstrategicoController {
    private final JdbcTemplate db;
    private final OkrService okrs;
    private final Autorizacao aut;
    public DashboardEstrategicoController(JdbcTemplate db, OkrService okrs, Autorizacao aut) { this.db = db; this.okrs = okrs; this.aut = aut; }

    @GetMapping("/api/dashboard/estrategico")
    Map<String, Object> estrategico(@RequestHeader("Authorization") String a) {
        Autorizacao.Contexto c = aut.contexto(a);
        Map<String, Object> r = new LinkedHashMap<>();
        if (c.pode("users.view"))
            r.put("funcionariosAtivos", db.queryForObject("SELECT COUNT(*) FROM usuarios WHERE empresa_id=? AND ativo", Integer.class, c.empresaId()));
        if (c.pode("squads.view")) {
            r.put("totalSquads", db.queryForObject("SELECT COUNT(*) FROM squads WHERE empresa_id=?", Integer.class, c.empresaId()));
            r.put("squadsAtivas", db.queryForObject("SELECT COUNT(*) FROM squads WHERE empresa_id=? AND status='ATIVA'", Integer.class, c.empresaId()));
        }
        if (c.pode("okrs.view")) {
            Map<String, Object> resumo = okrs.resumo(c.empresaId());
            r.putAll((Map<String, Object>) resumo.get("geral"));
            r.put("desempenhoPorSquad", resumo.get("porSquad"));
        }
        return r;
    }
}
