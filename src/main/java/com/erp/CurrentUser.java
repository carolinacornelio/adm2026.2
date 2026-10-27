package com.erp;

import org.springframework.stereotype.Component;
import java.util.Map;

/** Extrai o usuário autenticado do Bearer token enviado pelo frontend. */
@Component
public class CurrentUser {
    private final AuthService auth;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    public CurrentUser(AuthService auth, org.springframework.jdbc.core.JdbcTemplate db) { this.auth=auth; this.db=db; }
    /** Valida o header Authorization e recupera a sessão do token Bearer. */
    public Map<String,Object> get(String header) { if(header==null || !header.startsWith("Bearer ")) throw new SecurityException("Faça login para continuar."); return auth.usuario(header.substring(7)); }
    /** Retorna o tenant usado para filtrar todas as consultas SQL. */
    public int empresa(String header) { return ((Number)get(header).get("empresaId")).intValue(); }
    /** Retorna o usuário responsável pela operação. */
    public int usuario(String header) { return ((Number)get(header).get("id")).intValue(); }
    /** Permissões são relidas do banco para que uma troca de perfil vigore na sessão aberta. */
    public String perfil(String header) {
        var sessao=get(header);
        String perfil=db.queryForObject("SELECT COALESCE(perfil,'padrao') FROM usuarios WHERE id=? AND empresa_id=?",String.class,sessao.get("id"),sessao.get("empresaId"));
        return ComplianceService.normalizarPerfil(perfil);
    }
}
