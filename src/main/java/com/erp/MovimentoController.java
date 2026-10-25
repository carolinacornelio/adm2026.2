package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Centraliza movimentações de estoque, compras e vendas com controles estritos da Lei Anticorrupção:
 * - ETAPA 1.2: Rastreabilidade, 3-way matching e bloqueio de fornecedores com dados bloqueados.
 * - ETAPA 2.1: Alertas automáticos para valores > R$ 50.000,00 (ALT-01) e serviços sem contrato (ALT-02).
 * - ETAPA 2.2: Hierarquia de aprovação dinâmica com dupla alçada para valores >= R$ 10.000,00.
 * - ETAPA 1.1: Regra de Ouro SoD (quem aprova compra não pode processar o pagamento).
 */
@RestController
public class MovimentoController {

    private final JdbcTemplate db;
    private final CurrentUser user;
    private final ComplianceService compliance;

    public MovimentoController(JdbcTemplate db, CurrentUser user, ComplianceService compliance) {
        this.db = db;
        this.user = user;
        this.compliance = compliance;
    }

    /** Registra venda e dá saída no estoque. */
    @PostMapping("/api/vendas")
    @Transactional
    public Map<String, Object> venda(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) {
        return movimentar(d, a, true);
    }

    /** Registra compra e submete ao fluxo de alçadas e compliance. */
    @PostMapping("/api/compras")
    @Transactional
    public Map<String, Object> compra(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) {
        return movimentar(d, a, false);
    }

    @GetMapping("/api/compras")
    public List<Map<String, Object>> compras(@RequestHeader("Authorization") String a) {
        return db.queryForList("""
            SELECT 
                c.id, c.total, c.status, c.data,
                c.aprovador_nivel_1 AS "aprovadorNivel1",
                c.aprovador_nivel_2 AS "aprovadorNivel2",
                c.data_aprovacao_n1 AS "dataAprovacaoN1",
                c.data_aprovacao_n2 AS "dataAprovacaoN2",
                c.nota_fiscal AS "notaFiscal",
                c.tipo_documento AS "tipoDocumento",
                f.nome AS "fornecedorNome",
                u.nome AS "solicitanteNome"
            FROM compras c
            JOIN fornecedores f ON f.id = c.fornecedor_id
            JOIN usuarios u ON u.id = c.usuario_id
            WHERE c.empresa_id = ?
            ORDER BY c.id DESC
            """, user.empresa(a));
    }

    @GetMapping("/api/vendas")
    public List<Map<String, Object>> vendas(@RequestHeader("Authorization") String a) {
        return db.queryForList("SELECT id, total, status, data FROM vendas WHERE empresa_id=? ORDER BY id DESC", user.empresa(a));
    }

    @GetMapping("/api/estoque")
    public List<Map<String, Object>> estoque(@RequestHeader("Authorization") String a) {
        return db.queryForList("""
            SELECT p.id, p.nome, p.codigo,
                   COALESCE(e.quantidade,0) AS "quantidadeEstoque",
                   COALESCE(e.estoque_minimo,0) AS "estoqueMinimo",
                   CASE WHEN COALESCE(e.quantidade,0)<=0 THEN 'Esgotado' 
                        WHEN COALESCE(e.quantidade,0)<=COALESCE(e.estoque_minimo,0) THEN 'Baixo' 
                        ELSE 'OK' END AS situacao 
              FROM produtos p 
              LEFT JOIN estoque e ON e.produto_id=p.id AND e.empresa_id=p.empresa_id 
             WHERE p.empresa_id=? AND p.ativo=true
            """, user.empresa(a));
    }

    /**
     * ETAPA 2.2 & ETAPA 3.1: Hierarquia de aprovação dinâmica e teste de bloqueio de perfil operacional.
     */
    @PostMapping("/api/compras/{id}/aprovar")
    @Transactional
    public Map<String, Object> aprovarCompra(@PathVariable int id, @RequestHeader("Authorization") String a) {
        int e = user.empresa(a);
        int usuarioId = user.usuario(a);
        String perfil = user.perfil(a);

        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, total, status, aprovador_nivel_1, aprovador_nivel_2, usuario_id FROM compras WHERE id = ? AND empresa_id = ?",
            id, e
        );
        if (rows.isEmpty()) throw new IllegalArgumentException("Compra não encontrada.");

        Map<String, Object> compra = rows.get(0);
        double total = ((Number) compra.get("total")).doubleValue();
        String statusAtual = String.valueOf(compra.get("status"));
        Integer n1 = (Integer) compra.get("aprovador_nivel_1");
        Integer solicitanteId = (Integer) compra.get("usuario_id");

        // CENÁRIO ETAPA 3: Bloqueio estrito de usuário operacional tentando aprovar unilateralmente compras >= 10k
        if (total >= 10000.0) {
            if (perfil.equals("compras_operador") || perfil.equals("comprador") || 
                perfil.equals("operador") || perfil.equals("padrao") || perfil.contains("usuario_compras")) {
                
                try {
                    compliance.registrarAlerta(
                        e, usuarioId, "COMPRA_ID_" + id, total,
                        "TENTATIVA_ELEVACAO_PRIVILEGIO", "CRITICA",
                        "Usuário com perfil restrito de compras tentou aprovar unilateralmente pedido de alto valor sem alçada gerencial."
                    );
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                throw new ComplianceException(
                    "COMPLIANCE_ALCADA_VIOLATION",
                    "Violação de Compliance: O usuário atual não possui alçada ou nível hierárquico suficiente para aprovar transações acima de R$ 10.000,00."
                );
            }
        }

        // Validação de Alçadas
        if (n1 == null) {
            // Nível 1: Gerente ou Admin
            if (!perfil.equals("gerente_compras") && !perfil.equals("admin") && !perfil.equals("gerente") && !perfil.equals("diretor")) {
                throw new ComplianceException(
                    "COMPLIANCE_ALCADA_VIOLATION",
                    "Apenas gerentes ou administradores possuem alçada de Nível 1 para aprovação."
                );
            }

            if (total < 10000.0) {
                // Alçada simples concluída
                db.update("UPDATE compras SET aprovador_nivel_1 = ?, data_aprovacao_n1 = NOW(), status = 'CONCLUIDA' WHERE id = ?", usuarioId, id);
                efetivarEntradaEstoqueEContasPagar(id, e);
                return Map.of("id", id, "status", "CONCLUIDA", "mensagem", "Compra aprovada em alçada única e estoque atualizado.");
            } else {
                // Requer segundo nível
                db.update("UPDATE compras SET aprovador_nivel_1 = ?, data_aprovacao_n1 = NOW(), status = 'AGUARDANDO_NIVEL_2' WHERE id = ?", usuarioId, id);
                return Map.of("id", id, "status", "AGUARDANDO_NIVEL_2", "mensagem", "Aprovação Nível 1 registrada. Aguardando aprovação de segundo nível (Diretor/Compliance).");
            }
        } else {
            // Nível 2
            if (n1 == usuarioId || (solicitanteId != null && solicitanteId == usuarioId)) {
                throw new ComplianceException(
                    "COMPLIANCE_ALCADA_VIOLATION",
                    "Violação de Alçada: O segundo aprovador deve ser um usuário distinto do primeiro aprovador e do solicitante."
                );
            }
            if (!perfil.equals("diretor") && !perfil.equals("compliance") && !perfil.equals("admin")) {
                throw new ComplianceException(
                    "COMPLIANCE_ALCADA_VIOLATION",
                    "A aprovação de Nível 2 exige alçada de Diretor, Compliance ou Administrador."
                );
            }

            db.update("UPDATE compras SET aprovador_nivel_2 = ?, data_aprovacao_n2 = NOW(), status = 'CONCLUIDA' WHERE id = ?", usuarioId, id);
            efetivarEntradaEstoqueEContasPagar(id, e);
            return Map.of("id", id, "status", "CONCLUIDA", "mensagem", "Aprovação de alçada dupla concluída com sucesso. Estoque atualizado.");
        }
    }

    /**
     * ETAPA 1.1: Processamento de pagamento aplicando a Regra de Ouro SoD.
     */
    @PostMapping("/api/compras/{id}/pagar")
    @Transactional
    public Map<String, Object> pagarCompra(@PathVariable int id, @RequestHeader("Authorization") String a) {
        int e = user.empresa(a);
        int usuarioId = user.usuario(a);

        // Validação da Regra de Ouro SoD
        compliance.validarRegraDeOuroSoD(e, id, usuarioId);

        db.update("UPDATE compras SET status = 'PAGO' WHERE id = ? AND empresa_id = ?", id, e);
        db.update("UPDATE contas_pagar SET status = 'pago', data_pagamento = CURRENT_DATE, usuario_liquidacao_id = ? WHERE compra_id = ? AND empresa_id = ?",
                usuarioId, id, e);

        return Map.of("id", id, "status", "PAGO", "mensagem", "Pagamento liquidado com sucesso respeitando a segregação de funções.");
    }

    private Map<String, Object> movimentar(Map<String, Object> d, String a, boolean venda) {
        int e = user.empresa(a);
        int usuarioId = user.usuario(a);
        Object pessoa = d.get(venda ? "clienteId" : "fornecedorId");
        if (pessoa == null) throw new IllegalArgumentException("Informe o " + (venda ? "cliente" : "fornecedor") + ".");
        int idPessoa = inteiro(pessoa);
        String pessoaTabela = venda ? "clientes" : "fornecedores";

        List<Map<String, Object>> pessoaDados = db.queryForList("SELECT * FROM " + pessoaTabela + " WHERE id=? AND empresa_id=?", idPessoa, e);
        if (pessoaDados.isEmpty()) throw new IllegalArgumentException("Pessoa não encontrada para esta empresa.");

        if (!venda) {
            // ETAPA 1.3: Bloqueio de fornecedor caso esteja com dados bancários em quarentena/bloqueados
            Boolean bloqueado = (Boolean) pessoaDados.get(0).get("dados_bancarios_bloqueados");
            if (Boolean.TRUE.equals(bloqueado)) {
                throw new ComplianceException(
                    "COMPLIANCE_FORNECEDOR_BLOQUEADO",
                    "Fornecedor com dados bancários em análise preventiva. Movimentações suspensas até validação de compliance."
                );
            }
        }

        List<?> itens = itens(d.get("itens"));
        String tabela = venda ? "vendas" : "compras", itabela = venda ? "itens_venda" : "itens_compra", col = venda ? "cliente_id" : "fornecedor_id";
        LocalDate data = data(d.get("data"));

        String tipoDoc = d.getOrDefault("tipoDocumento", "MERCADORIA").toString().toUpperCase();
        String notaFiscal = d.getOrDefault("notaFiscal", "").toString();
        Integer contratoId = d.get("contratoId") != null && !d.get("contratoId").toString().isBlank() 
            ? inteiro(d.get("contratoId")) : null;

        Number mov;
        if (venda) {
            mov = db.queryForObject(
                "INSERT INTO vendas(empresa_id,cliente_id,usuario_id,total,data,status) VALUES(?,?,?,0,?,'CONCLUIDA') RETURNING id",
                Number.class, e, idPessoa, usuarioId, data
            );
        } else {
            mov = db.queryForObject(
                "INSERT INTO compras(empresa_id,fornecedor_id,usuario_id,total,data,status,tipo_documento,nota_fiscal,contrato_id) VALUES(?,?,?,0,?,'PENDENTE_CALCULO',?,?,?) RETURNING id",
                Number.class, e, idPessoa, usuarioId, data, tipoDoc, notaFiscal, contratoId
            );
        }

        double total = 0;
        String colunaPreco = venda ? "COALESCE(p.preco_venda, p.preco)" : "COALESCE(p.preco_compra, p.preco)";

        for (Object item : itens) {
            if (!(item instanceof Map<?, ?> bruto)) throw new IllegalArgumentException("Item da movimentação inválido.");
            Map<String, Object> i = new HashMap<>();
            bruto.forEach((chave, valor) -> i.put(String.valueOf(chave), valor));
            int p = inteiro(i.get("produtoId")), q = inteiro(i.get("quantidade"));
            if (q <= 0) throw new IllegalArgumentException("A quantidade deve ser maior que zero.");

            List<Map<String, Object>> produto = db.queryForList(
                "SELECT e.quantidade," + colunaPreco + " AS preco FROM estoque e JOIN produtos p ON p.id=e.produto_id WHERE e.produto_id=? AND e.empresa_id=? AND p.ativo=true FOR UPDATE OF e",
                p, e
            );
            if (produto.isEmpty()) throw new IllegalArgumentException("Produto não encontrado para esta empresa.");

            int atual = ((Number) produto.get(0).get("quantidade")).intValue();
            if (venda && atual < q) throw new IllegalArgumentException("Estoque insuficiente para o produto " + p + ".");

            double preco = ((Number) produto.get(0).get("preco")).doubleValue();
            total += q * preco;

            db.update("INSERT INTO " + itabela + "(" + (venda ? "venda_id" : "compra_id") + ",produto_id,quantidade,preco_unitario) VALUES(?,?,?,?)", mov, p, q, preco);

            // Vendas dão saída imediata no estoque
            if (venda) {
                db.update("UPDATE estoque SET quantidade=quantidade-?,updated_at=NOW() WHERE produto_id=? AND empresa_id=?", q, p, e);
                db.update("INSERT INTO movimentacoes_estoque(empresa_id,produto_id,tipo,quantidade,motivo) VALUES(?,?,?,?,?)", e, p, "SAIDA", q, "Venda #" + mov);
            }
        }

        // ETAPA 2.1: Validação de NF de Serviço sem contrato prévio (ALT-02)
        if (!venda && "SERVICO".equals(tipoDoc)) {
            compliance.validarNotaFiscalServico(e, usuarioId, total, contratoId);
        }

        String statusFinal;
        if (venda) {
            statusFinal = "CONCLUIDA";
            db.update("INSERT INTO contas_receber (empresa_id, venda_id, descricao, valor, vencimento, status) VALUES (?, ?, ?, ?, ?, 'pendente')",
                    e, mov, "Venda #" + mov, total, data.plusDays(30));
        } else {
            // ETAPA 2.1: Alerta automático ALT-01 para transações > R$ 50.000,00
            compliance.verificarAlertaValorExcessivo(e, usuarioId, "COMPRA_ID_" + mov, total);

            // ETAPA 2.2: Se compra for >= R$ 10.000,00, entra em alçada multinível
            if (total >= 10000.0) {
                statusFinal = "PENDENTE_APROVACAO";
            } else {
                statusFinal = "CONCLUIDA";
                efetivarEntradaEstoqueEContasPagar(mov.intValue(), e);
            }
        }

        db.update("UPDATE " + tabela + " SET total=?, status=? WHERE id=? AND empresa_id=?", total, statusFinal, mov, e);
        return Map.of("id", mov, "total", total, "status", statusFinal);
    }

    private void efetivarEntradaEstoqueEContasPagar(int compraId, int empresaId) {
        List<Map<String, Object>> itens = db.queryForList("SELECT produto_id, quantidade FROM itens_compra WHERE compra_id = ?", compraId);
        for (Map<String, Object> item : itens) {
            int p = ((Number) item.get("produto_id")).intValue();
            int q = ((Number) item.get("quantidade")).intValue();
            db.update("UPDATE estoque SET quantidade=quantidade+?, updated_at=NOW() WHERE produto_id=? AND empresa_id=?", q, p, empresaId);
            db.update("INSERT INTO movimentacoes_estoque(empresa_id,produto_id,tipo,quantidade,motivo) VALUES(?,?,?,?,?)", empresaId, p, "ENTRADA", q, "Compra #" + compraId);
        }

        double total = db.queryForObject("SELECT total FROM compras WHERE id = ?", Double.class, compraId);
        if (db.queryForObject("SELECT COUNT(*) FROM contas_pagar WHERE compra_id = ?", Integer.class, compraId) == 0) {
            db.update("INSERT INTO contas_pagar (empresa_id, compra_id, descricao, valor, vencimento, status) VALUES (?, ?, ?, ?, CURRENT_DATE + INTERVAL '30 days', 'pendente')",
                    empresaId, compraId, "Compra #" + compraId, total);
        }
    }

    private int inteiro(Object valor) {
        if (valor == null) throw new IllegalArgumentException("ID inválido.");
        return valor instanceof Number n ? n.intValue() : Integer.parseInt(valor.toString());
    }

    private List<?> itens(Object valor) {
        if (!(valor instanceof List<?> itens) || itens.isEmpty()) throw new IllegalArgumentException("Informe ao menos um item.");
        return itens;
    }

    private LocalDate data(Object valor) {
        if (valor == null || valor.toString().isBlank()) return LocalDate.now();
        try { return LocalDate.parse(valor.toString()); }
        catch (RuntimeException erro) { throw new IllegalArgumentException("Data inválida. Use o formato AAAA-MM-DD."); }
    }
}
