package com.erp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Cadastro de vários produtos em uma única requisição e uma única transação. */
@RestController
@RequestMapping("/api/produtos/lote")
public class ProdutoLoteController {

    private static final int MAX_ITENS = 200;

    private final JdbcTemplate jdbc;
    private final CurrentUser user;

    public ProdutoLoteController(JdbcTemplate jdbc, CurrentUser user) {
        this.jdbc = jdbc;
        this.user = user;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> criar(@RequestBody Map<String, Object> corpo,
                                     @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);

        if (!(corpo.get("itens") instanceof List<?> itens) || itens.isEmpty()) {
            throw new IllegalArgumentException("Envie ao menos um produto.");
        }
        if (itens.size() > MAX_ITENS) {
            throw new IllegalArgumentException("Envie no máximo " + MAX_ITENS + " produtos por vez.");
        }

        long base = System.currentTimeMillis();
        List<Object[]> produtos = new ArrayList<>();
        List<Object[]> estoques = new ArrayList<>();

        for (int i = 0; i < itens.size(); i++) {
            int linha = i + 1;
            if (!(itens.get(i) instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException("Linha " + linha + ": formato inválido.");
            }
            String nome = texto(item.get("nome"));
            if (nome.isEmpty()) throw new IllegalArgumentException("Linha " + linha + ": informe o nome.");
            if (nome.length() > 150) throw new IllegalArgumentException("Linha " + linha + ": nome muito longo.");

            double venda = preco(item.get("precoVenda"), linha, "preço de venda", true);
            double compra = preco(item.get("precoCompra"), linha, "preço de compra", false);
            int minimo = inteiro(item.get("estoqueMinimo"), linha);

            String codigo = texto(item.get("codigo"));
            if (codigo.isEmpty()) codigo = "COD-" + base + "-" + linha;
            if (codigo.length() > 50) throw new IllegalArgumentException("Linha " + linha + ": código muito longo.");

            produtos.add(new Object[]{empresaId, nome, codigo, venda, compra, venda});
            estoques.add(new Object[]{empresaId, empresaId, codigo, minimo});
        }

        jdbc.batchUpdate(
                "INSERT INTO produtos (empresa_id, nome, codigo, preco, preco_compra, preco_venda, descricao, ativo) "
                        + "VALUES (?, ?, ?, ?, ?, ?, '', true)", produtos);
        jdbc.batchUpdate(
                "INSERT INTO estoque (empresa_id, produto_id, quantidade, estoque_minimo) "
                        + "VALUES (?, (SELECT id FROM produtos WHERE empresa_id = ? AND codigo = ?), 0, ?)", estoques);

        return Map.of("total", itens.size());
    }

    private static String texto(Object valor) {
        return valor == null ? "" : valor.toString().trim();
    }

    private static double preco(Object valor, int linha, String campo, boolean obrigatorio) {
        String t = texto(valor).replace(',', '.');
        if (t.isEmpty()) {
            if (obrigatorio) throw new IllegalArgumentException("Linha " + linha + ": informe o " + campo + ".");
            return 0;
        }
        try {
            double d = Double.parseDouble(t);
            if (!Double.isFinite(d) || d < 0) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Linha " + linha + ": " + campo + " inválido.");
        }
    }

    private static int inteiro(Object valor, int linha) {
        String t = texto(valor);
        if (t.isEmpty()) return 0;
        try {
            int n = Integer.parseInt(t);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Linha " + linha + ": estoque mínimo inválido.");
        }
    }
}
