package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autenticação atual. A senha é armazenada com BCrypt e o token aleatório fica
 * em memória. É adequado para desenvolvimento; para produção, substituir por JWT.
 */
@Service
public class AuthService {
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Map<String,Object>> sessoes = new ConcurrentHashMap<>();
    public AuthService(JdbcTemplate db) { this.db = db; }

    /** Cria a empresa e o primeiro usuário; a senha é salva somente como hash BCrypt. */
    public Map<String,Object> cadastrar(Map<String,Object> d) {
        String email = texto(d, "email").toLowerCase();
        if (db.queryForObject("SELECT COUNT(*) FROM usuarios WHERE email=?", Integer.class, email) > 0)
            throw new IllegalArgumentException("Já existe uma conta cadastrada com este e-mail.");
        Number empresaId = db.queryForObject("INSERT INTO empresas (nome_fantasia) VALUES (?) RETURNING id", Number.class, texto(d,"empresa"));
        Number usuarioId = db.queryForObject("INSERT INTO usuarios (empresa_id,nome,email,senha_hash,perfil) VALUES (?,?,?,?,?) RETURNING id", Number.class, empresaId, texto(d,"nome"), email, encoder.encode(texto(d,"senha")), "padrao");
        return loginPorId(usuarioId.intValue(), empresaId.intValue(), texto(d,"nome"), email,
                planoDaEmpresa(empresaId.intValue()));
    }
    /** Busca o usuário, compara o hash BCrypt e abre uma sessão. */
    public Map<String,Object> login(Map<String,Object> d) {
        String email = texto(d,"email").toLowerCase();
        List<Map<String,Object>> rows = db.queryForList("""
                SELECT u.id, u.empresa_id, u.nome, u.email, u.senha_hash,
                       COALESCE(e.plano_assinatura, 'BASE') AS plano
                  FROM usuarios u
                  JOIN empresas e ON e.id = u.empresa_id
                 WHERE u.email = ?
                """, email);
        if (rows.isEmpty() || !encoder.matches(texto(d,"senha"), (String) rows.get(0).get("senha_hash"))) throw new SecurityException("E-mail ou senha inválidos.");
        Map<String,Object> u=rows.get(0); return loginPorId(((Number)u.get("id")).intValue(), ((Number)u.get("empresa_id")).intValue(), (String)u.get("nome"), (String)u.get("email"), String.valueOf(u.get("plano")));
    }
    public Map<String,Object> usuario(String token) { Map<String,Object> u=sessoes.get(token); if(u==null) throw new SecurityException("Sessão inválida ou expirada."); return u; }
    private String planoDaEmpresa(int empresaId) {
        return PlanService.normalizar(db.queryForObject(
                "SELECT COALESCE(plano_assinatura, 'BASE') FROM empresas WHERE id=?",
                String.class,
                empresaId));
    }
    private Map<String,Object> loginPorId(int id,int empresa,String nome,String email,String plano) { String token=UUID.randomUUID().toString(); Map<String,Object> u=Map.of("id",id,"empresaId",empresa,"nome",nome,"email",email,"plano",PlanService.normalizar(plano)); sessoes.put(token,u); return Map.of("token",token,"usuario",u); }
    static String texto(Map<String,Object> d,String k) { Object v=d.get(k); if(v==null || v.toString().isBlank()) throw new IllegalArgumentException("Campo obrigatório: "+k); return v.toString().trim(); }
}
