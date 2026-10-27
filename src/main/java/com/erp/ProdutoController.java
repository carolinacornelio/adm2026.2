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
    private final PosRealtimeService realtime;

    public ProdutoController(JdbcTemplate db, CurrentUser user, PlanService plans, PosRealtimeService realtime) {
        this.db = db;
        this.user = user;
        this.plans = plans;
        this.realtime = realtime;
    }

    /** Lista produtos ativos e, opcionalmente, filtra por categoria. */
    @GetMapping
    public List<Map<String, Object>> listar(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) Integer categoriaId) {
        int empresaId = user.empresa(authorization);
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.nome, p.codigo, p.descricao, p.controla_validade AS "controlaValidade",
                       COALESCE(p.preco_venda, p.preco) AS "precoVenda",
                       COALESCE(p.preco_compra, p.preco) AS "precoCompra",
                       p.categoria_id AS "categoriaId",
                       COALESCE(c.nome, 'Sem Categoria') AS "categoriaNome",
                       """ + InventarioService.DISPONIVEL_SQL + """
                       AS "quantidadeEstoque",
                       COALESCE(e.estoque_minimo, 0) AS "estoqueMinimo", COALESCE(e.quantidade,0) AS "quantidadeTotal"
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
        return ProdutoValidade.aplicar(db, empresaId, db.queryForList(sql.toString(), parametros.toArray()));
    }

    /** Busca um produto ativo, devolvendo também sua categoria. */
    @GetMapping("/{id}")
    public Map<String, Object> buscar(
            @PathVariable int id,
            @RequestHeader("Authorization") String authorization) {
        List<Map<String, Object>> produtos = db.queryForList("""
                SELECT p.id, p.nome, p.codigo, p.descricao, p.controla_validade AS "controlaValidade",
                       COALESCE(p.preco_venda, p.preco) AS "precoVenda",
                       COALESCE(p.preco_compra, p.preco) AS "precoCompra",
                       p.categoria_id AS "categoriaId",
                       COALESCE(c.nome, 'Sem Categoria') AS "categoriaNome",
                       """ + InventarioService.DISPONIVEL_SQL + """
                       AS "quantidadeEstoque",
                       COALESCE(e.estoque_minimo, 0) AS "estoqueMinimo", COALESCE(e.quantidade,0) AS "quantidadeTotal"
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
        return ProdutoValidade.aplicar(db, user.empresa(authorization), produtos).get(0);
    }

    /** Cria o produto e seu registro inicial de estoque em uma transação. */
    @PostMapping
    @Transactional
    public Map<String, Object> criar(
            @RequestBody Map<String, Object> dados,
            @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        String nome = AuthService.texto(dados, "nome");
        validarTextoProduto(nome, "nome", 150);
        String codigo = java.util.Objects.toString(dados.get("codigo"), "").trim();
        validarTextoProduto(codigo, "código", 50);
        java.math.BigDecimal precoCompra = precoValido(dados, "precoCompra");
        java.math.BigDecimal precoVenda = precoValido(dados, "precoVenda");
        Integer categoriaId = categoriaId(dados, empresaId);
        if (codigo.isBlank()) codigo = "COD-" + java.util.UUID.randomUUID();

        Number id = db.queryForObject("""
                INSERT INTO produtos (empresa_id, nome, codigo, preco, preco_compra, preco_venda, categoria_id, descricao, ativo, controla_validade)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, ?)
                RETURNING id
                """, Number.class, empresaId, nome, codigo, precoVenda, precoCompra, precoVenda,
                categoriaId, descricaoValida(dados), Boolean.TRUE.equals(controleValidade(dados)));

        db.update("INSERT INTO estoque (empresa_id, produto_id, quantidade, estoque_minimo) VALUES (?, ?, 0, ?)",
                empresaId, id, intNumero(dados, "estoqueMinimo"));
        realtime.publicarDepoisDoCommit(empresaId, "catalogo-atualizado");
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
        bloquearProduto(id,empresaId);
        Integer categoriaId = categoriaId(dados, empresaId);
        String nome = AuthService.texto(dados, "nome");
        validarTextoProduto(nome, "nome", 150);
        String codigo = java.util.Objects.toString(dados.get("codigo"), "").trim();
        if (codigo.isBlank()) codigo = "COD-" + id;
        validarTextoProduto(codigo, "código", 50);
        java.math.BigDecimal precoCompra = precoValido(dados, "precoCompra");
        java.math.BigDecimal precoVenda = precoValido(dados, "precoVenda");

        int alterados = db.update("""
                UPDATE produtos
                   SET nome=?, codigo=?, preco=?, preco_compra=?, preco_venda=?, categoria_id=?, descricao=?, controla_validade=COALESCE(?,controla_validade)
                 WHERE id=? AND empresa_id=? AND ativo=true
                """, nome, codigo, precoVenda, precoCompra, precoVenda, categoriaId,
                descricaoValida(dados), controleValidade(dados), id, empresaId);
        if (alterados == 0) throw new IllegalArgumentException("Produto não encontrado.");

        db.update("UPDATE estoque SET estoque_minimo=? WHERE produto_id=? AND empresa_id=?",
                intNumero(dados, "estoqueMinimo"), id, empresaId);
        realtime.publicarDepoisDoCommit(empresaId, "catalogo-atualizado");
        return buscar(id, authorization);
    }

    /** Exclusão lógica: mantém o histórico de compras e vendas. */
    @DeleteMapping("/{id}")
    @Transactional
    public void remover(@PathVariable int id, @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        bloquearProduto(id,empresaId);
        if (db.update("UPDATE produtos SET ativo=false WHERE id=? AND empresa_id=?", id, empresaId) == 0) {
            throw new IllegalArgumentException("Produto não encontrado.");
        }
        realtime.publicarDepoisDoCommit(empresaId, "catalogo-atualizado");
    }

    private Integer categoriaId(Map<String, Object> dados, int empresaId) {
        Object valor = dados.get("categoriaId");
        if (valor == null || String.valueOf(valor).isBlank()) return null;
        plans.exigir(empresaId, "PLUS");
        int id;
        try {
            id = new java.math.BigDecimal(valor.toString()).intValueExact();
            if(id<=0) throw new NumberFormatException();
        } catch (ArithmeticException | NumberFormatException erro) {
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

    static int intNumero(Map<String, Object> dados, String chave) {
        try {
            int valor = new java.math.BigDecimal(String.valueOf(dados.getOrDefault(chave, 0)).replace(',', '.')).intValueExact();
            if (valor < 0) throw new NumberFormatException();
            return valor;
        } catch (ArithmeticException | NumberFormatException erro) {
            throw new IllegalArgumentException("Valor inteiro não negativo inválido: " + chave);
        }
    }

    private java.math.BigDecimal precoValido(Map<String, Object> dados, String chave) {
        try {
            var valor=new java.math.BigDecimal(String.valueOf(dados.getOrDefault(chave,0)).replace(',','.'))
                    .setScale(2,java.math.RoundingMode.UNNECESSARY);
            if(valor.signum()<0 || valor.compareTo(new java.math.BigDecimal("99999999.99"))>0)throw new NumberFormatException();
            return valor;
        } catch (ArithmeticException | NumberFormatException erro) {
            throw new IllegalArgumentException("Preço inválido: " + chave + ". Informe até duas casas decimais.");
        }
    }

    private void bloquearProduto(int id,int empresa) {
        db.queryForList("SELECT e.id FROM estoque e JOIN produtos p ON p.id=e.produto_id AND p.empresa_id=e.empresa_id WHERE p.id=? AND p.empresa_id=? ORDER BY e.id FOR UPDATE OF e,p",id,empresa);
    }

    private String descricaoValida(Map<String, Object> dados) {
        String descricao = java.util.Objects.toString(dados.get("descricao"), "").trim();
        if (descricao.length() > 10_000) {
            throw new IllegalArgumentException("A descrição deve ter até 10000 caracteres.");
        }
        return descricao;
    }

    private void validarTextoProduto(String valor, String campo, int tamanhoMaximo) {
        if (valor.length() > tamanhoMaximo) {
            throw new IllegalArgumentException("O campo " + campo + " deve ter até " + tamanhoMaximo + " caracteres.");
        }
    }
    private Boolean controleValidade(Map<String,Object> dados) {
        Object valor=dados.get("controlaValidade");
        if(valor==null)return null;
        if(valor instanceof Boolean controle)return controle;
        throw new IllegalArgumentException("Informe verdadeiro ou falso para o controle de validade.");
    }
}
