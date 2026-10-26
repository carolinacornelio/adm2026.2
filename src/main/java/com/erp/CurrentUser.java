package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Extrai o usuário autenticado do Bearer token enviado pelo frontend. */
@Component
public class CurrentUser {
    private final AuthService auth;
    private final JdbcTemplate db;
    public CurrentUser(AuthService auth, JdbcTemplate db) { 
        this.auth = auth; 
        this.db = db;
    }
    /** Valida o header Authorization e recupera a sessão do token Bearer. */
    public Map<String,Object> get(String header) { if(header==null || !header.startsWith("Bearer ")) throw new SecurityException("Faça login para continuar."); return auth.usuario(header.substring(7)); }
    /** Retorna o tenant usado para filtrar todas as consultas SQL. */
    public int empresa(String header) { return ((Number)get(header).get("empresaId")).intValue(); }
    /** Retorna o usuário responsável pela operação. */
    public int usuario(String header) { return ((Number)get(header).get("id")).intValue(); }
    /** Retorna o perfil do usuário ou busca no banco de dados. */
    public String perfil(String header) {
        Object p = get(header).get("perfil");
        if (p != null && !p.toString().isBlank() && !p.toString().equalsIgnoreCase("padrao")) {
            return p.toString().toLowerCase();
        }
        try {
            int uid = usuario(header);
            String dbPerfil = db.queryForObject("SELECT COALESCE(perfil, 'padrao') FROM usuarios WHERE id=?", String.class, uid);
            return dbPerfil != null ? dbPerfil.toLowerCase() : "padrao";
        } catch (Exception e) {
            return p != null ? p.toString().toLowerCase() : "padrao";
        }
    }
}
