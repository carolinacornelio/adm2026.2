package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Ponto único de identidade e autorização das requisições.
 *
 * <p>O contexto é montado a partir do token Bearer (via {@link CurrentUser}); a empresa
 * NUNCA vem do corpo ou da URL. A cada chamada o banco confirma que o usuário e a empresa
 * continuam ativos, então desativar um funcionário vale imediatamente, mesmo com sessão antiga.
 */
@Component
public class Autorizacao {
    /** Identidade resolvida da requisição. {@code permissoes} já inclui todo o catálogo para o proprietário. */
    public record Contexto(int usuarioId, int empresaId, boolean proprietario, Set<String> permissoes) {
        public boolean pode(String permissao) { return proprietario || permissoes.contains(permissao); }
    }

    private final JdbcTemplate db;
    private final CurrentUser user;
    public Autorizacao(JdbcTemplate db, CurrentUser user) { this.db = db; this.user = user; }

    /** Resolve usuário, empresa e permissões; recusa sessão inválida, conta ou empresa desativada. */
    public Contexto contexto(String authorization) {
        int usuarioId = user.usuario(authorization);
        int empresaId = user.empresa(authorization);
        List<Map<String, Object>> linhas = db.queryForList("""
                SELECT u.ativo, u.proprietario, e.status
                  FROM usuarios u JOIN empresas e ON e.id = u.empresa_id
                 WHERE u.id = ? AND u.empresa_id = ?
                """, usuarioId, empresaId);
        if (linhas.isEmpty()) throw new SecurityException("Sessão inválida ou expirada.");
        Map<String, Object> u = linhas.get(0);
        if (!Boolean.TRUE.equals(u.get("ativo"))) throw new SecurityException("Conta desativada. Procure o proprietário da empresa.");
        if (!"ATIVA".equals(u.get("status"))) throw new SecurityException("A conta da empresa está inativa.");
        boolean proprietario = Boolean.TRUE.equals(u.get("proprietario"));
        return new Contexto(usuarioId, empresaId, proprietario, proprietario ? todas() : doUsuario(usuarioId, empresaId));
    }

    /** Exige a permissão; lança 403 se faltar. Use no início de qualquer operação protegida. */
    public Contexto exigir(String authorization, String permissao) {
        Contexto c = contexto(authorization);
        if (!c.pode(permissao)) throw new AcessoNegadoException("Você não tem permissão para esta operação.");
        return c;
    }

    private Set<String> todas() {
        return new TreeSet<>(db.queryForList("SELECT codigo FROM permissoes", String.class));
    }

    private Set<String> doUsuario(int usuarioId, int empresaId) {
        return new TreeSet<>(db.queryForList("""
                SELECT DISTINCT p.codigo
                  FROM usuario_perfis up
                  JOIN perfis_acesso pa ON pa.id = up.perfil_id AND pa.empresa_id = up.empresa_id AND pa.ativo
                  JOIN perfil_permissoes pp ON pp.perfil_id = pa.id
                  JOIN permissoes p ON p.id = pp.permissao_id
                 WHERE up.usuario_id = ? AND up.empresa_id = ?
                """, String.class, usuarioId, empresaId));
    }
}
