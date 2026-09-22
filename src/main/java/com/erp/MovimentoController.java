package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Centraliza operações que alteram estoque.
 *
 * Compra soma quantidades; venda subtrai.
 * O @Transactional garante rollback se qualquer item falhar,
 * evitando compra/venda gravada pela metade.
 */
@RestController
public class MovimentoController {

    private final JdbcTemplate db;
    private final CurrentUser user;

    public MovimentoController(JdbcTemplate db, CurrentUser user) {
        this.db = db;
        this.user = user;
    }

    /** Registra venda e dá saída no estoque. */
    @PostMapping("/api/vendas")
    @Transactional
    Map<String, Object> venda(
            @RequestBody Map<String, Object> d,
            @RequestHeader("Authorization") String a) {

        return movimentar(d, a, true);
    }

    /** Registra compra e dá entrada no estoque. */
    @PostMapping("/api/compras")
    @Transactional
    Map<String, Object> compra(
            @RequestBody Map<String, Object> d,
            @RequestHeader("Authorization") String a) {

        return movimentar(d, a, false);
    }

    /** Lista as compras da empresa logada. */
    @GetMapping("/api/compras")
    List<Map<String, Object>> compras(
            @RequestHeader("Authorization") String a) {

        return db.queryForList(
                "SELECT id, total, status, data " +
                "FROM compras " +
                "WHERE empresa_id=? " +
                "ORDER BY id DESC",
                user.empresa(a)
        );
    }

    /** Lista as vendas da empresa logada. */
    @GetMapping("/api/vendas")
    List<Map<String, Object>> vendas(
            @RequestHeader("Authorization") String a) {

        return db.queryForList(
                "SELECT id, total, status, data " +
                "FROM vendas " +
                "WHERE empresa_id=? " +
                "ORDER BY id DESC",
                user.empresa(a)
        );
    }

    /** Lista o estoque da empresa logada. */
    @GetMapping("/api/estoque")
    List<Map<String, Object>> estoque(
            @RequestHeader("Authorization") String a) {

        return db.queryForList(
                "SELECT " +
                "p.id, " +
                "p.nome, " +
                "p.codigo, " +
                "COALESCE(e.quantidade,0) AS \"quantidadeEstoque\", " +
                "COALESCE(e.estoque_minimo,0) AS \"estoqueMinimo\", " +
                "CASE " +
                "WHEN COALESCE(e.quantidade,0)<=0 THEN 'Esgotado' " +
                "WHEN COALESCE(e.quantidade,0)<=COALESCE(e.estoque_minimo,0) THEN 'Baixo' " +
                "ELSE 'OK' " +
                "END AS situacao " +
                "FROM produtos p " +
                "LEFT JOIN estoque e " +
                "ON e.produto_id=p.id " +
                "AND e.empresa_id=p.empresa_id " +
                "WHERE p.empresa_id=? " +
                "AND p.ativo=true",
                user.empresa(a)
        );
    }

    /**
     * Executa uma movimentação.
     *
     * venda = true  -> saída de estoque
     * venda = false -> entrada de estoque
     */
    private Map<String, Object> movimentar(
            Map<String, Object> d,
            String a,
            boolean venda) {

        int empresaId = user.empresa(a);

        Object pessoa = d.get(
                venda ? "clienteId" : "fornecedorId"
        );

        if (pessoa == null) {
            throw new IllegalArgumentException(
                    "Informe o " +
                    (venda ? "cliente" : "fornecedor") +
                    "."
            );
        }

        int idPessoa = inteiro(pessoa);

        String pessoaTabela = venda
                ? "clientes"
                : "fornecedores";

        Integer quantidadePessoa = db.queryForObject(
                "SELECT COUNT(*) FROM " +
                pessoaTabela +
                " WHERE id=? AND empresa_id=?",
                Integer.class,
                idPessoa,
                empresaId
        );

        if (quantidadePessoa == null || quantidadePessoa == 0) {
            throw new IllegalArgumentException(
                    "Pessoa não encontrada para esta empresa."
            );
        }

        /*
         * Obtém os itens enviados pelo frontend
         * sem fazer unchecked cast.
         */
        Object itensObj = d.get("itens");

        if (!(itensObj instanceof List<?> lista)) {
            throw new IllegalArgumentException(
                    "Informe ao menos um item."
            );
        }

        List<Map<String, Object>> itens = new ArrayList<>();

        /*
         * Valida cada item individualmente.
         */
        for (Object item : lista) {

            if (!(item instanceof Map<?, ?> mapa)) {
                throw new IllegalArgumentException(
                        "Item inválido."
                );
            }

            Map<String, Object> itemConvertido = new HashMap<>();

            for (Map.Entry<?, ?> entrada : mapa.entrySet()) {

                if (!(entrada.getKey() instanceof String chave)) {
                    throw new IllegalArgumentException(
                            "Chave de item inválida."
                    );
                }

                itemConvertido.put(
                        chave,
                        entrada.getValue()
                );
            }

            itens.add(itemConvertido);
        }

        if (itens.isEmpty()) {
            throw new IllegalArgumentException(
                    "Informe ao menos um item."
            );
        }

        String tabela = venda
                ? "vendas"
                : "compras";

        String tabelaItens = venda
                ? "itens_venda"
                : "itens_compra";

        String colunaPessoa = venda
                ? "cliente_id"
                : "fornecedor_id";

        Number movimentoId = db.queryForObject(
                "INSERT INTO " +
                tabela +
                "(empresa_id," +
                colunaPessoa +
                ",usuario_id,total) " +
                "VALUES(?,?,?,0) " +
                "RETURNING id",
                Number.class,
                empresaId,
                idPessoa,
                user.usuario(a)
        );

        if (movimentoId == null) {
            throw new IllegalStateException(
                    "Não foi possível criar a movimentação."
            );
        }

        double total = 0;

        for (Map<String, Object> item : itens) {

            Object produtoObj = item.get("produtoId");
            Object quantidadeObj = item.get("quantidade");

            int produtoId = inteiro(produtoObj);
            int quantidade = inteiro(quantidadeObj);

            if (quantidade <= 0) {
                throw new IllegalArgumentException(
                        "A quantidade deve ser maior que zero."
                );
            }

            List<Map<String, Object>> produto =
                    db.queryForList(
                            "SELECT " +
                            "e.quantidade, " +
                            "p.preco " +
                            "FROM estoque e " +
                            "JOIN produtos p " +
                            "ON p.id=e.produto_id " +
                            "WHERE e.produto_id=? " +
                            "AND e.empresa_id=? " +
                            "AND p.ativo=true",
                            produtoId,
                            empresaId
                    );

            if (produto.isEmpty()) {
                throw new IllegalArgumentException(
                        "Produto não encontrado para esta empresa."
                );
            }

            Object quantidadeAtualObj =
                    produto.get(0).get("quantidade");

            if (!(quantidadeAtualObj instanceof Number quantidadeAtualNumber)) {
                throw new IllegalArgumentException(
                        "Quantidade de estoque inválida."
                );
            }

            int quantidadeAtual =
                    quantidadeAtualNumber.intValue();

            if (venda && quantidadeAtual < quantidade) {
                throw new IllegalArgumentException(
                        "Estoque insuficiente para o produto " +
                        produtoId +
                        "."
                );
            }

            /*
             * O preço é sempre o cadastrado no produto.
             * O navegador não decide o valor.
             */
            Object precoObj =
                    produto.get(0).get("preco");

            if (!(precoObj instanceof Number precoNumber)) {
                throw new IllegalArgumentException(
                        "Preço do produto inválido."
                );
            }

            double preco = precoNumber.doubleValue();

            total += quantidade * preco;

            db.update(
                    "INSERT INTO " +
                    tabelaItens +
                    "(" +
                    (venda ? "venda_id" : "compra_id") +
                    ",produto_id,quantidade,preco_unitario) " +
                    "VALUES(?,?,?,?)",
                    movimentoId,
                    produtoId,
                    quantidade,
                    preco
            );

            db.update(
                    "UPDATE estoque " +
                    "SET quantidade=quantidade+?, " +
                    "updated_at=NOW() " +
                    "WHERE produto_id=? " +
                    "AND empresa_id=?",
                    venda ? -quantidade : quantidade,
                    produtoId,
                    empresaId
            );
        }

        db.update(
                "UPDATE " +
                tabela +
                " SET total=? " +
                "WHERE id=? " +
                "AND empresa_id=?",
                total,
                movimentoId,
                empresaId
        );

        return Map.of(
                "id",
                movimentoId,
                "total",
                total
        );
    }

    /**
     * Converte um valor recebido do JSON para inteiro.
     */
    private int inteiro(Object valor) {

        if (valor == null) {
            throw new IllegalArgumentException(
                    "ID inválido."
            );
        }

        if (valor instanceof Number numero) {
            return numero.intValue();
        }

        return Integer.parseInt(
                valor.toString()
        );
    }
}