package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** CRUD de categorias, sempre isolado pela empresa do usuário autenticado. */
@RestController
@RequestMapping("/api/categorias")
public class CategoriaController {
    private final JdbcTemplate db;
    private final CurrentUser user;
    private final PlanService plans;
    private final PosRealtimeService realtime;

    public CategoriaController(JdbcTemplate db, CurrentUser user, PlanService plans, PosRealtimeService realtime) {
        this.db = db;
        this.user = user;
        this.plans = plans;
        this.realtime = realtime;
    }

    /** Lista as categorias em ordem alfabética para os selects do frontend. */
    @GetMapping
    public List<Map<String, Object>> listar(@RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        plans.exigir(empresaId, "PLUS");
        var categorias = db.queryForList("""
                SELECT c.id, c.nome, COUNT(p.id) AS quantidade_produtos
                  FROM categorias c
             LEFT JOIN produtos p ON p.categoria_id = c.id
                                 AND p.empresa_id = c.empresa_id
                                 AND p.ativo = true
                 WHERE c.empresa_id = ?
              GROUP BY c.id, c.nome
              ORDER BY LOWER(c.nome), c.id
                """, empresaId);
        var produtos = ProdutoValidade.aplicar(db, empresaId, db.queryForList(
                "SELECT p.id,p.categoria_id,p.controla_validade AS \"controlaValidade\",COALESCE(e.quantidade,0) AS \"quantidadeTotal\","
                + InventarioService.DISPONIVEL_SQL + " AS \"quantidadeEstoque\",COALESCE(e.estoque_minimo,0) AS \"estoqueMinimo\""
                + " FROM produtos p LEFT JOIN estoque e ON e.produto_id=p.id AND e.empresa_id=p.empresa_id WHERE p.empresa_id=? AND p.ativo=true", empresaId));
        for (var categoria : categorias) {
            var vinculados = produtos.stream().filter(p -> categoria.get("id").equals(p.get("categoria_id"))).toList();
            categoria.put("estoqueOk", vinculados.stream().filter(p -> "OK".equals(p.get("situacaoEstoque"))).count());
            categoria.put("esgotados", vinculados.stream().filter(p -> "Esgotado".equals(p.get("situacaoEstoque"))).count());
            categoria.put("estoqueEmAlerta", vinculados.stream().filter(p -> !"OK".equals(p.get("situacaoEstoque")) && !"Esgotado".equals(p.get("situacaoEstoque"))).count());
            categoria.put("proximosMinimo", vinculados.stream().filter(p -> "Próximo do mínimo".equals(p.get("situacaoEstoque"))).count());
            categoria.put("noMinimo", vinculados.stream().filter(p -> "No estoque mínimo".equals(p.get("situacaoEstoque"))).count());
            categoria.put("abaixoMinimo", vinculados.stream().filter(p -> "Abaixo do mínimo".equals(p.get("situacaoEstoque"))).count());
            categoria.put("validadeEmAlerta", vinculados.stream().filter(p -> java.util.Set.of("BLOQUEADO","PRIORIDADE","IMPORTANTE","NORMAL").contains(p.get("nivelValidade"))).count());
        }
        return categorias;
    }

    /** Cria uma categoria sem permitir duplicidade dentro da mesma empresa. */
    @PostMapping
    @Transactional
    public Map<String, Object> criar(
            @RequestBody Map<String, Object> dados,
            @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        plans.exigir(empresaId, "PLUS");
        String nome = nomeValido(dados);
        db.queryForList("SELECT pg_advisory_xact_lock(74129, ?)",empresaId);
        if (existe(empresaId, nome, null)) {
            throw new IllegalArgumentException("Já existe uma categoria com esse nome.");
        }

        Number id = db.queryForObject(
                "INSERT INTO categorias (empresa_id, nome) VALUES (?, ?) RETURNING id",
                Number.class, empresaId, nome);
        realtime.publicarDepoisDoCommit(empresaId, "catalogo-atualizado");
        return Map.of("id", id, "nome", nome, "quantidadeProdutos", 0);
    }

    /** Renomeia uma categoria preservando os produtos já vinculados. */
    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> atualizar(
            @PathVariable int id,
            @RequestBody Map<String, Object> dados,
            @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        plans.exigir(empresaId, "PLUS");
        String nome = nomeValido(dados);
        db.queryForList("SELECT pg_advisory_xact_lock(74129, ?)",empresaId);
        if (existe(empresaId, nome, id)) {
            throw new IllegalArgumentException("Já existe outra categoria com esse nome.");
        }

        int alteradas = db.update("UPDATE categorias SET nome=? WHERE id=? AND empresa_id=?", nome, id, empresaId);
        if (alteradas == 0) throw new IllegalArgumentException("Categoria não encontrada.");
        realtime.publicarDepoisDoCommit(empresaId, "catalogo-atualizado");
        return Map.of("id", id, "nome", nome);
    }

    /** Exclui a categoria; o banco deixa categoria_id nulo nos produtos ligados. */
    @DeleteMapping("/{id}")
    @Transactional
    public void remover(@PathVariable int id, @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        plans.exigir(empresaId, "PLUS");
        if (db.update("DELETE FROM categorias WHERE id=? AND empresa_id=?", id, empresaId) == 0) {
            throw new IllegalArgumentException("Categoria não encontrada.");
        }
        realtime.publicarDepoisDoCommit(empresaId, "catalogo-atualizado");
    }

    private boolean existe(int empresaId, String nome, Integer idIgnorado) {
        String sql = "SELECT COUNT(*) FROM categorias WHERE empresa_id=? AND LOWER(TRIM(nome))=LOWER(TRIM(?))";
        if (idIgnorado == null) return db.queryForObject(sql, Integer.class, empresaId, nome) > 0;
        return db.queryForObject(sql + " AND id<>?", Integer.class, empresaId, nome, idIgnorado) > 0;
    }

    private String nomeValido(Map<String, Object> dados) {
        String nome = AuthService.texto(dados, "nome");
        if (nome.length() > 100) throw new IllegalArgumentException("O nome da categoria deve ter até 100 caracteres.");
        return nome;
    }
}
