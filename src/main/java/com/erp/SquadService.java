package com.erp;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Equipes de trabalho de uma empresa. Toda consulta e alteração é filtrada pela empresa do token. */
@Service
public class SquadService {
    static final Set<String> STATUS = Set.of("ATIVA", "INATIVA");
    private final JdbcTemplate db;
    public SquadService(JdbcTemplate db) { this.db = db; }

    private static final String SELECT = """
            SELECT s.id, s.nome, s.descricao, s.status, s.lider_id AS "liderId", l.nome AS "liderNome",
                   s.created_at AS "criadoEm",
                   (SELECT COUNT(*) FROM squad_membros sm JOIN usuarios u ON u.id = sm.usuario_id AND u.empresa_id = sm.empresa_id
                     WHERE sm.squad_id = s.id AND sm.empresa_id = s.empresa_id AND u.ativo) AS "totalIntegrantes"
              FROM squads s LEFT JOIN usuarios l ON l.id = s.lider_id AND l.empresa_id = s.empresa_id
            """;

    public List<Map<String, Object>> listar(int empresa, String status) {
        if (status != null && !status.isBlank())
            return db.queryForList(SELECT + " WHERE s.empresa_id = ? AND s.status = ? ORDER BY LOWER(s.nome)", empresa, Dados.opcao(status, STATUS, "status"));
        return db.queryForList(SELECT + " WHERE s.empresa_id = ? ORDER BY LOWER(s.nome)", empresa);
    }

    public Map<String, Object> buscar(int empresa, int id) {
        List<Map<String, Object>> r = db.queryForList(SELECT + " WHERE s.empresa_id = ? AND s.id = ?", empresa, id);
        if (r.isEmpty()) throw new NaoEncontradoException("Squad não encontrada.");
        Map<String, Object> s = r.get(0);
        s.put("integrantes", db.queryForList("""
                SELECT u.id, u.nome, u.email, u.ativo, (s.lider_id = u.id) AS lider, sm.created_at AS "desde"
                  FROM squad_membros sm
                  JOIN squads s ON s.id = sm.squad_id AND s.empresa_id = sm.empresa_id
                  JOIN usuarios u ON u.id = sm.usuario_id AND u.empresa_id = sm.empresa_id
                 WHERE sm.squad_id = ? AND sm.empresa_id = ?
                 ORDER BY (s.lider_id = u.id) DESC, LOWER(u.nome)
                """, id, empresa));
        return s;
    }

    /** Funcionários ativos da empresa que ainda não integram a Squad. */
    public List<Map<String, Object>> candidatos(int empresa, int squadId) {
        exigir(empresa, squadId);
        return db.queryForList("""
                SELECT u.id, u.nome, u.email FROM usuarios u
                 WHERE u.empresa_id = ? AND u.ativo
                   AND NOT EXISTS (SELECT 1 FROM squad_membros sm WHERE sm.squad_id = ? AND sm.usuario_id = u.id)
                 ORDER BY LOWER(u.nome)
                """, empresa, squadId);
    }

    @Transactional
    public Map<String, Object> criar(Autorizacao.Contexto c, Map<String, Object> d) {
        String nome = Dados.texto(d, "nome", 100, true), descricao = Dados.texto(d, "descricao", 500, false);
        Integer lider = Dados.inteiro(d, "liderId", false);
        List<Integer> membros = Dados.inteiros(d, "integrantes");
        if (lider != null) { exigirFuncionarioAtivo(c.empresaId(), lider); }
        for (Integer m : membros) exigirFuncionarioAtivo(c.empresaId(), m);
        int id;
        try {
            id = db.queryForObject("INSERT INTO squads(empresa_id, nome, descricao, lider_id, status) VALUES (?,?,?,?, 'ATIVA') RETURNING id", Integer.class, c.empresaId(), nome, descricao, lider);
        } catch (DuplicateKeyException e) { throw new IllegalArgumentException("Já existe uma Squad com este nome."); }
        for (Integer m : membros) adicionar(c.empresaId(), id, m);
        if (lider != null) adicionar(c.empresaId(), id, lider); // o líder sempre integra a Squad
        return buscar(c.empresaId(), id);
    }

    @Transactional
    public Map<String, Object> atualizar(int empresa, int id, Map<String, Object> d) {
        Map<String, Object> atual = buscar(empresa, id);
        String nome = d.containsKey("nome") ? Dados.texto(d, "nome", 100, true) : (String) atual.get("nome");
        String descricao = d.containsKey("descricao") ? Dados.texto(d, "descricao", 500, false) : (String) atual.get("descricao");
        String status = d.containsKey("status") ? Dados.opcao(String.valueOf(d.get("status")), STATUS, "status") : (String) atual.get("status");
        try {
            db.update("UPDATE squads SET nome=?, descricao=?, status=?, updated_at=NOW() WHERE id=? AND empresa_id=?", nome, descricao, status, id, empresa);
        } catch (DuplicateKeyException e) { throw new IllegalArgumentException("Já existe uma Squad com este nome."); }
        return buscar(empresa, id);
    }

    @Transactional
    public Map<String, Object> adicionarIntegrantes(int empresa, int id, List<Integer> usuarios) {
        exigir(empresa, id);
        if (usuarios.isEmpty()) throw new IllegalArgumentException("Informe ao menos um funcionário.");
        for (Integer u : usuarios) { exigirFuncionarioAtivo(empresa, u); adicionar(empresa, id, u); }
        return buscar(empresa, id);
    }

    /** Remove o integrante; se ele era o líder, a Squad fica sem líder. */
    @Transactional
    public Map<String, Object> removerIntegrante(int empresa, int id, int usuario) {
        exigir(empresa, id);
        if (db.update("DELETE FROM squad_membros WHERE squad_id=? AND usuario_id=? AND empresa_id=?", id, usuario, empresa) == 0)
            throw new NaoEncontradoException("Integrante não encontrado nesta Squad.");
        db.update("UPDATE squads SET lider_id = NULL, updated_at = NOW() WHERE id=? AND empresa_id=? AND lider_id=?", id, empresa, usuario);
        return buscar(empresa, id);
    }

    /** Define (ou, com {@code null}, remove) o líder; o novo líder passa a integrar a Squad. */
    @Transactional
    public Map<String, Object> definirLider(int empresa, int id, Integer usuario) {
        exigir(empresa, id);
        if (usuario != null) { exigirFuncionarioAtivo(empresa, usuario); adicionar(empresa, id, usuario); }
        db.update("UPDATE squads SET lider_id=?, updated_at=NOW() WHERE id=? AND empresa_id=?", usuario, id, empresa);
        return buscar(empresa, id);
    }

    // ---- internos --------------------------------------------------------------------------------------

    private void exigir(int empresa, int id) {
        if (db.queryForObject("SELECT COUNT(*) FROM squads WHERE id=? AND empresa_id=?", Integer.class, id, empresa) == 0)
            throw new NaoEncontradoException("Squad não encontrada.");
    }

    /** O funcionário precisa existir NESTA empresa e estar ativo. */
    private void exigirFuncionarioAtivo(int empresa, int usuario) {
        if (db.queryForObject("SELECT COUNT(*) FROM usuarios WHERE id=? AND empresa_id=? AND ativo", Integer.class, usuario, empresa) == 0)
            throw new IllegalArgumentException("Funcionário inválido ou inativo: " + usuario);
    }

    private void adicionar(int empresa, int squad, int usuario) {
        db.update("INSERT INTO squad_membros(squad_id, usuario_id, empresa_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", squad, usuario, empresa);
    }
}
