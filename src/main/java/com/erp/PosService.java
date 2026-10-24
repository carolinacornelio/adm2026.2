package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** A venda do POS usa as mesmas tabelas de venda, estoque e financeiro do ERP. */
@Service
public class PosService {
    private final JdbcTemplate db;
    private final PosRealtimeService realtime;
    private final InventarioService inventario;
    public PosService(JdbcTemplate db, PosRealtimeService realtime, InventarioService inventario) { this.db = db; this.realtime = realtime; this.inventario=inventario; }

    @Transactional
    public Map<String, Object> finalizar(PosController.Venda pedido, int empresa, int operador) {
        TreeMap<Integer, Integer> quantidades = new TreeMap<>();
        for (var item : pedido.itens()) {
            int quantidade = quantidades.getOrDefault(item.produtoId(), 0) + item.quantidade();
            if (quantidade > 1000000) throw new IllegalArgumentException("Quantidade máxima por produto excedida.");
            quantidades.put(item.produtoId(), quantidade);
        }
        String hash = assinatura(pedido, quantidades, operador);
        // Serializa repetições da mesma operação, inclusive em instâncias distintas da API.
        db.queryForList("SELECT pg_advisory_xact_lock(?, hashtext(?))", empresa, pedido.chaveOperacao().toString());
        var existentes = db.queryForList("SELECT id, pos_requisicao_hash FROM vendas WHERE empresa_id=? AND pos_chave_operacao=?", empresa, pedido.chaveOperacao());
        if (!existentes.isEmpty()) {
            if (!hash.equals(existentes.get(0).get("pos_requisicao_hash")))
                throw new IllegalArgumentException("Esta operação já foi usada para outra venda. Consulte o histórico antes de continuar.");
            return buscar(((Number) existentes.get(0).get("id")).intValue(), empresa);
        }
        if (pedido.clienteId() != null && db.queryForObject("SELECT COUNT(*) FROM clientes WHERE id=? AND empresa_id=?", Integer.class, pedido.clienteId(), empresa) != 1)
            throw new IllegalArgumentException("Cliente não encontrado para esta empresa.");

        BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
        List<Map<String, Object>> itens = new ArrayList<>();
        // Ordem estável de locks evita deadlocks entre dois caixas com os mesmos produtos.
        for (var entrada : quantidades.entrySet()) {
            var produtos = db.queryForList("""
                    SELECT p.id, p.nome, p.codigo, COALESCE(p.preco_venda, p.preco) AS preco,
                           e.id AS estoque_id, e.quantidade
                      FROM produtos p JOIN estoque e ON e.produto_id=p.id AND e.empresa_id=p.empresa_id
                     WHERE p.id=? AND p.empresa_id=? AND p.ativo=true
                     ORDER BY e.id FOR UPDATE OF e, p
                    """, entrada.getKey(), empresa);
            if (produtos.size() != 1) throw new IllegalArgumentException("Produto indisponível ou estoque inconsistente: " + entrada.getKey() + ". Atualize o catálogo.");
            var produto = new LinkedHashMap<>(produtos.get(0));
            if (((Number) produto.get("quantidade")).intValue() < entrada.getValue())
                throw new IllegalArgumentException("Estoque insuficiente para " + produto.get("nome") + ". Atualize o catálogo.");
            BigDecimal preco = (BigDecimal) produto.get("preco");
            if (preco.signum() < 0) throw new IllegalArgumentException("Preço inválido para " + produto.get("nome") + ".");
            subtotal = subtotal.add(preco.multiply(BigDecimal.valueOf(entrada.getValue())));
            produto.put("quantidade", entrada.getValue());
            itens.add(produto);
        }
        BigDecimal total = subtotal.subtract(pedido.desconto());
        if (total.signum() < 0 || subtotal.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new IllegalArgumentException("Desconto ou total inválido.");
        if (total.compareTo(pedido.totalEsperado()) != 0)
            throw new IllegalArgumentException("O preço dos produtos mudou. Atualize o catálogo e confira o total antes de confirmar novamente.");
        if (!Boolean.TRUE.equals(pedido.pagamentoConfirmado())) throw new IllegalArgumentException("Confirme o recebimento antes de finalizar.");
        boolean dinheiro = "DINHEIRO".equals(pedido.formaPagamento());
        if ((dinheiro && pedido.valorRecebido().compareTo(total) < 0) || (!dinheiro && pedido.valorRecebido().compareTo(total) != 0))
            throw new IllegalArgumentException("Valor recebido incompatível com o total da venda.");
        BigDecimal troco = dinheiro ? pedido.valorRecebido().subtract(total) : BigDecimal.ZERO;
        int venda = db.queryForObject("""
                INSERT INTO vendas (empresa_id,cliente_id,usuario_id,status,total,data,origem,pos_subtotal,pos_desconto,
                    pos_forma_pagamento,pos_valor_recebido,pos_troco,pos_chave_operacao,pos_requisicao_hash)
                VALUES (?,?,?,'concluida',?,CURRENT_TIMESTAMP,'POS',?,?,?,?,?,?,?) RETURNING id
                """, Integer.class, empresa, pedido.clienteId(), operador, total, subtotal, pedido.desconto(),
                pedido.formaPagamento(), pedido.valorRecebido(), troco, pedido.chaveOperacao(), hash);
        for (var item : itens) {
            db.update("INSERT INTO itens_venda (venda_id,produto_id,quantidade,preco_unitario,pos_nome_produto,pos_codigo_produto) VALUES (?,?,?,?,?,?)",
                    venda, item.get("id"), item.get("quantidade"), item.get("preco"), item.get("nome"), item.get("codigo"));
            inventario.baixar(empresa,((Number)item.get("id")).intValue(),((Number)item.get("quantidade")).intValue(),venda);
        }
        db.update("""
                INSERT INTO contas_receber (empresa_id,venda_id,descricao,valor,vencimento,status,data_recebimento)
                VALUES (?,?,?,?,CURRENT_DATE,'recebido',CURRENT_DATE)
                """, empresa, venda, "Venda POS #" + venda + " - " + pedido.formaPagamento(), total);
        var resposta = buscar(venda, empresa);
        realtime.publicarDepoisDoCommit(empresa, "estoque-atualizado");
        return resposta;
    }

    public List<Map<String, Object>> listar(int empresa) {
        return db.queryForList("""
                SELECT v.id,v.total,v.data,v.status,v.pos_forma_pagamento AS "formaPagamento",
                       COALESCE(c.nome,'Consumidor final') AS cliente
                  FROM vendas v LEFT JOIN clientes c ON c.id=v.cliente_id AND c.empresa_id=v.empresa_id
                 WHERE v.empresa_id=? AND v.origem='POS' ORDER BY v.id DESC LIMIT 50
                """, empresa);
    }
    public Map<String, Object> buscar(int venda, int empresa) {
        var rows = db.queryForList("""
                SELECT v.id,v.total,v.data,v.status,v.pos_subtotal AS subtotal,v.pos_desconto AS desconto,
                       v.pos_forma_pagamento AS "formaPagamento",v.pos_valor_recebido AS "valorRecebido",v.pos_troco AS troco,
                       COALESCE(c.nome,'Consumidor final') AS cliente,u.nome AS operador,e.nome_fantasia AS empresa
                  FROM vendas v JOIN empresas e ON e.id=v.empresa_id
                  JOIN usuarios u ON u.id=v.usuario_id AND u.empresa_id=v.empresa_id
                  LEFT JOIN clientes c ON c.id=v.cliente_id AND c.empresa_id=v.empresa_id
                 WHERE v.id=? AND v.empresa_id=? AND v.origem='POS'
                """, venda, empresa);
        if (rows.isEmpty()) throw new IllegalArgumentException("Venda POS não encontrada para esta empresa.");
        var resposta = new LinkedHashMap<>(rows.get(0));
        resposta.put("itens", db.queryForList("""
                SELECT i.produto_id AS "produtoId",i.pos_nome_produto AS nome,i.pos_codigo_produto AS codigo,
                       i.quantidade,i.preco_unitario AS "precoUnitario",i.quantidade*i.preco_unitario AS subtotal
                  FROM itens_venda i JOIN vendas v ON v.id=i.venda_id
                 WHERE i.venda_id=? AND v.empresa_id=? ORDER BY i.id
                """, venda, empresa));
        return resposta;
    }
    private String assinatura(PosController.Venda pedido, TreeMap<Integer, Integer> itens, int operador) {
        String texto = operador + "|" + pedido.clienteId() + "|" + itens + "|" + pedido.desconto().stripTrailingZeros().toPlainString()
                + "|" + pedido.totalEsperado().stripTrailingZeros().toPlainString() + "|" + pedido.formaPagamento()
                + "|" + pedido.valorRecebido().stripTrailingZeros().toPlainString() + "|" + pedido.pagamentoConfirmado();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException erro) { throw new IllegalStateException(erro); }
    }
}
