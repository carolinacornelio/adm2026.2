package com.erp;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Dados da empresa do usuário autenticado. Plano e status não são editáveis aqui (cobrança/suporte). */
@Service
public class EmpresaService {
    private final JdbcTemplate db;
    public EmpresaService(JdbcTemplate db) { this.db = db; }

    public Map<String, Object> buscar(int empresa) {
        List<Map<String, Object>> r = db.queryForList("""
                SELECT e.id, e.nome_fantasia AS "nomeFantasia", e.documento, COALESCE(e.plano_assinatura,'BASE') AS plano,
                       e.status, e.created_at AS "criadoEm",
                       (SELECT COUNT(*) FROM usuarios u WHERE u.empresa_id = e.id AND u.ativo) AS "funcionariosAtivos",
                       (SELECT COUNT(*) FROM usuarios u WHERE u.empresa_id = e.id AND NOT u.ativo) AS "funcionariosInativos"
                  FROM empresas e WHERE e.id = ?
                """, empresa);
        if (r.isEmpty()) throw new NaoEncontradoException("Empresa não encontrada.");
        Map<String, Object> e = r.get(0);
        e.put("proprietario", db.queryForList("SELECT id, nome, email FROM usuarios WHERE empresa_id = ? AND proprietario", empresa)
                .stream().findFirst().orElse(null));
        return e;
    }

    /** Só nome fantasia e documento. A empresa vem do token do autor. */
    public Map<String, Object> atualizar(int empresa, Map<String, Object> d) {
        String nome = Dados.texto(d, "nomeFantasia", 150, true);
        String documento = Dados.texto(d, "documento", 20, false);
        try {
            db.update("UPDATE empresas SET nome_fantasia = ?, documento = ?, updated_at = NOW() WHERE id = ?", nome, documento.isEmpty() ? null : documento, empresa);
        } catch (DuplicateKeyException e) { throw new IllegalArgumentException("Já existe uma empresa com este documento."); }
        return buscar(empresa);
    }
}
