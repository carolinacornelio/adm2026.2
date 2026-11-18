package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Perfis de acesso por empresa e seus vínculos com o catálogo global de permissões. */
@Service
public class PerfilService {
    private final JdbcTemplate db;
    public PerfilService(JdbcTemplate db) { this.db = db; }

    /** Cria Proprietário, Gerente, Vendedor, Estoquista e Financeiro para uma empresa nova (idempotente). */
    static void criarPerfisIniciais(JdbcTemplate db, int empresaId) {
        db.update("""
                WITH novos AS (
                    INSERT INTO perfis_acesso(empresa_id, nome, descricao, sistema, chave_sistema)
                    SELECT CAST(? AS INTEGER), p.nome, p.descricao, true, p.chave
                      FROM (VALUES
                        ('PROPRIETARIO','Proprietário','Acesso administrativo completo.'),
                        ('GERENTE','Gerente','Opera todos os módulos, exceto administração de usuários e perfis.'),
                        ('VENDEDOR','Vendedor','Vendas, PDV e consulta de produtos e clientes.'),
                        ('ESTOQUISTA','Estoquista','Produtos, compras e estoque.'),
                        ('FINANCEIRO','Financeiro','Contas a pagar e a receber.')
                      ) AS p(chave, nome, descricao)
                     WHERE NOT EXISTS (SELECT 1 FROM perfis_acesso x WHERE x.empresa_id = CAST(? AS INTEGER)
                                          AND (x.chave_sistema = p.chave OR LOWER(x.nome) = LOWER(p.nome)))
                    RETURNING id, chave_sistema
                )
                INSERT INTO perfil_permissoes(perfil_id, permissao_id)
                SELECT n.id, pe.id FROM novos n
                  JOIN perfis_modelo_permissoes m ON m.chave = n.chave_sistema
                  JOIN permissoes pe ON pe.codigo = m.permissao_codigo
                ON CONFLICT DO NOTHING
                """, empresaId, empresaId);
    }

    /** Vincula o usuário a um perfil inicial da própria empresa (chave: PROPRIETARIO, GERENTE, ...). */
    static void atribuirPerfilSistema(JdbcTemplate db, int empresaId, int usuarioId, String chave) {
        db.update("""
                INSERT INTO usuario_perfis(usuario_id, perfil_id, empresa_id)
                SELECT ?, id, empresa_id FROM perfis_acesso WHERE empresa_id = ? AND chave_sistema = ?
                ON CONFLICT DO NOTHING
                """, usuarioId, empresaId, chave);
    }

    public List<Map<String, Object>> catalogo() {
        return db.queryForList("SELECT codigo, modulo, descricao FROM permissoes ORDER BY modulo, codigo");
    }

    public List<Map<String, Object>> listar(int empresa) {
        List<Map<String, Object>> perfis = db.queryForList(SELECT + " WHERE pa.empresa_id = ? ORDER BY pa.sistema DESC, pa.nome", empresa);
        perfis.forEach(p -> p.put("permissoes", permissoesDo(empresa, ((Number) p.get("id")).intValue())));
        return perfis;
    }

    public Map<String, Object> buscar(int empresa, int id) {
        List<Map<String, Object>> r = db.queryForList(SELECT + " WHERE pa.empresa_id = ? AND pa.id = ?", empresa, id);
        if (r.isEmpty()) throw new NaoEncontradoException("Perfil não encontrado.");
        r.get(0).put("permissoes", permissoesDo(empresa, id));
        return r.get(0);
    }

    @Transactional
    public Map<String, Object> criar(Autorizacao.Contexto c, Map<String, Object> d) {
        String nome = Dados.texto(d, "nome", 60, true), descricao = Dados.texto(d, "descricao", 200, false);
        List<Integer> permissoes = idsPermissoes(c, Dados.textos(d, "permissoes"));
        Integer id;
        try {
            id = db.queryForObject("INSERT INTO perfis_acesso(empresa_id, nome, descricao) VALUES (?,?,?) RETURNING id", Integer.class, c.empresaId(), nome, descricao);
        } catch (org.springframework.dao.DuplicateKeyException e) { throw new IllegalArgumentException("Já existe um perfil com este nome."); }
        gravarPermissoes(id, permissoes);
        return buscar(c.empresaId(), id);
    }

    @Transactional
    public Map<String, Object> atualizar(Autorizacao.Contexto c, int id, Map<String, Object> d) {
        Map<String, Object> atual = buscar(c.empresaId(), id);
        if ("PROPRIETARIO".equals(atual.get("chaveSistema"))) throw new IllegalArgumentException("O perfil Proprietário não pode ser alterado.");
        if (!c.proprietario() && perfilExcede(c, id)) throw new AcessoNegadoException("Este perfil concede permissões que você não possui.");
        String nome = d.containsKey("nome") ? Dados.texto(d, "nome", 60, true) : (String) atual.get("nome");
        String descricao = d.containsKey("descricao") ? Dados.texto(d, "descricao", 200, false) : (String) atual.get("descricao");
        List<Integer> permissoes = d.containsKey("permissoes") ? idsPermissoes(c, Dados.textos(d, "permissoes")) : null;
        try {
            db.update("UPDATE perfis_acesso SET nome=?, descricao=?, ativo=COALESCE(?, ativo) WHERE id=? AND empresa_id=?",
                    nome, descricao, d.get("ativo") instanceof Boolean b ? b : null, id, c.empresaId());
        } catch (org.springframework.dao.DuplicateKeyException e) { throw new IllegalArgumentException("Já existe um perfil com este nome."); }
        if (permissoes != null) {
            db.update("DELETE FROM perfil_permissoes WHERE perfil_id=?", id);
            gravarPermissoes(id, permissoes);
        }
        return buscar(c.empresaId(), id);
    }

    @Transactional
    public void excluir(Autorizacao.Contexto c, int id) {
        Map<String, Object> atual = buscar(c.empresaId(), id);
        if (Boolean.TRUE.equals(atual.get("sistema"))) throw new IllegalArgumentException("Perfis iniciais não podem ser excluídos; desative-os se necessário.");
        if (((Number) atual.get("totalUsuarios")).intValue() > 0) throw new IllegalArgumentException("Há funcionários vinculados a este perfil. Troque o perfil deles antes de excluir.");
        db.update("DELETE FROM perfis_acesso WHERE id=? AND empresa_id=?", id, c.empresaId());
    }

    /** True se o perfil contém alguma permissão que o usuário não possui (impede escalada de privilégio). */
    boolean perfilExcede(Autorizacao.Contexto c, int perfilId) {
        if (c.proprietario()) return false;
        return permissoesDo(c.empresaId(), perfilId).stream().anyMatch(p -> !c.permissoes().contains(p));
    }

    private static final String SELECT = """
            SELECT pa.id, pa.nome, pa.descricao, pa.sistema, pa.chave_sistema AS "chaveSistema", pa.ativo,
                   pa.created_at AS "criadoEm",
                   (SELECT COUNT(*) FROM usuario_perfis up WHERE up.perfil_id = pa.id) AS "totalUsuarios"
              FROM perfis_acesso pa
            """;

    List<String> permissoesDo(int empresa, int perfilId) {
        return db.queryForList("""
                SELECT p.codigo FROM perfil_permissoes pp
                  JOIN perfis_acesso pa ON pa.id = pp.perfil_id
                  JOIN permissoes p ON p.id = pp.permissao_id
                 WHERE pp.perfil_id = ? AND pa.empresa_id = ? ORDER BY p.codigo
                """, String.class, perfilId, empresa);
    }

    /** Converte códigos em ids, recusando códigos inexistentes e (para não proprietários) permissões que o autor não tem. */
    private List<Integer> idsPermissoes(Autorizacao.Contexto c, List<String> codigos) {
        List<Integer> ids = new ArrayList<>();
        for (String codigo : codigos) {
            List<Integer> r = db.queryForList("SELECT id FROM permissoes WHERE codigo=?", Integer.class, codigo);
            if (r.isEmpty()) throw new IllegalArgumentException("Permissão desconhecida: " + codigo);
            if (!c.pode(codigo)) throw new AcessoNegadoException("Você não pode conceder a permissão " + codigo + ", pois não a possui.");
            ids.add(r.get(0));
        }
        return ids;
    }

    private void gravarPermissoes(int perfilId, List<Integer> permissaoIds) {
        for (Integer pid : permissaoIds) db.update("INSERT INTO perfil_permissoes(perfil_id, permissao_id) VALUES (?,?) ON CONFLICT DO NOTHING", perfilId, pid);
    }
}
