package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** CRUD de produtos, incluindo vínculo opcional com uma categoria da empresa. */
@RestController
@RequestMapping("/api/produtos")
public class ProdutoController {
    private final JdbcTemplate db;
    private final CurrentUser user;
    private final PlanService plans;

    public ProdutoController(JdbcTemplate db, CurrentUser user, PlanService plans) {
        this.db = db;
        this.user = user;
        this.plans = plans;
    }

    /** Lista produtos ativos e, opcionalmente, filtra por categoria. */
    @GetMapping
    public List<Map<String, Object>> listar(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) Integer categoriaId) {
        int empresaId = user.empresa(authorization);
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.nome, p.codigo, p.descricao, p.preco AS "precoVenda",
                       p.preco AS "precoCompra", p.categoria_id AS "categoriaId",
                       COALESCE(c.nome, 'Sem Categoria') AS "categoriaNome",
                       COALESCE(e.quantidade, 0) AS "quantidadeEstoque",
                       COALESCE(e.estoque_minimo, 0) AS "estoqueMinimo"
                  FROM produtos p
             LEFT JOIN categorias c ON c.id = p.categoria_id
                                  AND c.empresa_id = p.empresa_id
             LEFT JOIN estoque e ON e.produto_id = p.id
                               AND e.empresa_id = p.empresa_id
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
                """);
        List<Object> parametros = new ArrayList<>(List.of(empresaId));

        if (categoriaId != null) {
            plans.exigir(empresaId, "PLUS");
            validarCategoria(categoriaId, empresaId);
            sql.append(" AND p.categoria_id = ?");
            parametros.add(categoriaId);
        }

        sql.append(" ORDER BY p.id");
        return db.queryForList(sql.toString(), parametros.toArray());
    }

    /** Busca um produto ativo, devolvendo também sua categoria. */
    @GetMapping("/{id}")
    public Map<String, Object> buscar(
            @PathVariable int id,
            @RequestHeader("Authorization") String authorization) {
        List<Map<String, Object>> produtos = db.queryForList("""
                SELECT p.id, p.nome, p.codigo, p.descricao, p.preco AS "precoVenda",
                       p.preco AS "precoCompra", p.categoria_id AS "categoriaId",
                       COALESCE(c.nome, 'Sem Categoria') AS "categoriaNome",
                       COALESCE(e.quantidade, 0) AS "quantidadeEstoque",
                       COALESCE(e.estoque_minimo, 0) AS "estoqueMinimo"
                  FROM produtos p
             LEFT JOIN categorias c ON c.id = p.categoria_id
                                  AND c.empresa_id = p.empresa_id
             LEFT JOIN estoque e ON e.produto_id = p.id
                               AND e.empresa_id = p.empresa_id
                 WHERE p.id = ?
                   AND p.empresa_id = ?
                   AND p.ativo = true
                """, id, user.empresa(authorization));
        if (produtos.isEmpty()) throw new IllegalArgumentException("Produto não encontrado.");
        return produtos.get(0);
    }

    /** Cria o produto e seu registro inicial de estoque em uma transação. */
    @PostMapping
    @Transactional
    public Map<String, Object> criar(
            @RequestBody Map<String, Object> dados,
            @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        String nome = AuthService.texto(dados, "nome");
        double preco = numero(dados, "precoVenda");
        Integer categoriaId = categoriaId(dados, empresaId);
        String codigo = String.valueOf(dados.getOrDefault("codigo", ""));
        if (codigo.isBlank()) codigo = "COD-" + System.currentTimeMillis();

        Number id = db.queryForObject("""
                INSERT INTO produtos (empresa_id, nome, codigo, preco, categoria_id, descricao, ativo)
                VALUES (?, ?, ?, ?, ?, ?, true)
                RETURNING id
                """, Number.class, empresaId, nome, codigo, preco, categoriaId,
                dados.getOrDefault("descricao", ""));

        db.update("INSERT INTO estoque (empresa_id, produto_id, quantidade, estoque_minimo) VALUES (?, ?, 0, ?)",
                empresaId, id, intNumero(dados, "estoqueMinimo"));
        return buscar(id.intValue(), authorization);
    }

    /** Atualiza dados do produto e seu estoque mínimo. */
    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> atualizar(
            @PathVariable int id,
            @RequestBody Map<String, Object> dados,
            @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        Integer categoriaId = categoriaId(dados, empresaId);
        String codigo = String.valueOf(dados.getOrDefault("codigo", ""));
        if (codigo.isBlank()) codigo = "COD-" + id;

        int alterados = db.update("""
                UPDATE produtos
                   SET nome=?, codigo=?, preco=?, categoria_id=?, descricao=?
                 WHERE id=? AND empresa_id=? AND ativo=true
                """, AuthService.texto(dados, "nome"), codigo, numero(dados, "precoVenda"),
                categoriaId, dados.getOrDefault("descricao", ""), id, empresaId);
        if (alterados == 0) throw new IllegalArgumentException("Produto não encontrado.");

        db.update("UPDATE estoque SET estoque_minimo=? WHERE produto_id=? AND empresa_id=?",
                intNumero(dados, "estoqueMinimo"), id, empresaId);
        return buscar(id, authorization);
    }

    /** Exclusão lógica: mantém o histórico de compras e vendas. */
    @DeleteMapping("/{id}")
    public void remover(@PathVariable int id, @RequestHeader("Authorization") String authorization) {
        if (db.update("UPDATE produtos SET ativo=false WHERE id=? AND empresa_id=?", id, user.empresa(authorization)) == 0) {
            throw new IllegalArgumentException("Produto não encontrado.");
        }
    }

    private Integer categoriaId(Map<String, Object> dados, int empresaId) {
        Object valor = dados.get("categoriaId");
        if (valor == null || String.valueOf(valor).isBlank()) return null;
        plans.exigir(empresaId, "PLUS");
        int id;
        try {
            id = valor instanceof Number numero ? numero.intValue() : Integer.parseInt(valor.toString());
        } catch (NumberFormatException erro) {
            throw new IllegalArgumentException("Categoria inválida.");
        }
        validarCategoria(id, empresaId);
        return id;
    }

    private void validarCategoria(int categoriaId, int empresaId) {
        if (db.queryForObject("SELECT COUNT(*) FROM categorias WHERE id=? AND empresa_id=?",
                Integer.class, categoriaId, empresaId) == 0) {
            throw new IllegalArgumentException("Categoria não encontrada para esta empresa.");
        }
    }

    static double numero(Map<String, Object> dados, String chave) {
        try {
            return Double.parseDouble(String.valueOf(dados.getOrDefault(chave, 0)).replace(',', '.'));
        } catch (NumberFormatException erro) {
            throw new IllegalArgumentException("Valor numérico inválido: " + chave);
        }
    }

    static int intNumero(Map<String, Object> dados, String chave) {
        return (int) numero(dados, chave);
    }
}
