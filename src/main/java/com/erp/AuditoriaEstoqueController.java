package com.erp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Auditoria de estoque (Desafio 5 - Controle de Qualidade).
 *
 * Compara o estoque gravado na tabela "estoque" com o saldo calculado a partir
 * do histórico de transações ("movimentacoes_estoque"): entradas somam, saídas
 * subtraem e ajustes do inventário físico somam ou subtraem conforme o sentido.
 * Se os dois não batem, alguma regra gravou um valor diferente do registrado no
 * histórico — o sintoma da "ruptura de estoque fantasma".
 *
 * Divergências também são registradas no log do servidor (nível WARN).
 */
@RestController
@RequestMapping("/api/estoque")
public class AuditoriaEstoqueController {
    private static final Logger log = LoggerFactory.getLogger(AuditoriaEstoqueController.class);

    /** Sinal de cada transação no saldo: ENTRADA (+), SAIDA (-), AJUSTE (+ se positivo, - se negativo). */
    private static final String SINAL_SQL = """
            CASE WHEN tipo = 'ENTRADA' THEN quantidade
                 WHEN tipo = 'AJUSTE' AND motivo LIKE '%ajuste positivo%' THEN quantidade
                 ELSE -quantidade END""";

    private final JdbcTemplate db;
    private final CurrentUser user;

    public AuditoriaEstoqueController(JdbcTemplate db, CurrentUser user) {
        this.db = db;
        this.user = user;
    }

    /** Estoque no sistema x estoque calculado pelo histórico, produto a produto. */
    @GetMapping("/auditoria")
    public List<Map<String, Object>> auditoria(@RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        List<Map<String, Object>> linhas = db.queryForList("""
                SELECT p.id,
                       p.nome,
                       COALESCE(h.entradas, 0) AS "entradas",
                       COALESCE(h.saidas, 0) AS "saidas",
                       COALESCE(h.ajustes, 0) AS "ajustes",
                       COALESCE(h.saldo, 0) AS "estoqueHistorico",
                       COALESCE(e.quantidade, 0) AS "estoqueSistema",
                       COALESCE(e.quantidade, 0) - COALESCE(h.saldo, 0) AS "diferenca"
                  FROM produtos p
             LEFT JOIN estoque e ON e.produto_id = p.id AND e.empresa_id = p.empresa_id
             LEFT JOIN (SELECT produto_id,
                               SUM(CASE WHEN tipo = 'ENTRADA' THEN quantidade ELSE 0 END) AS entradas,
                               SUM(CASE WHEN tipo = 'SAIDA' THEN quantidade ELSE 0 END) AS saidas,
                               SUM(CASE WHEN tipo = 'AJUSTE' THEN (""" + SINAL_SQL + """
                               ) ELSE 0 END) AS ajustes,
                               SUM(""" + SINAL_SQL + """
                               ) AS saldo
                          FROM movimentacoes_estoque
                         WHERE empresa_id = ?
                      GROUP BY produto_id) h ON h.produto_id = p.id
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
              ORDER BY p.nome
                """, empresaId, empresaId);

        for (Map<String, Object> linha : linhas) {
            long diferenca = ((Number) linha.get("diferenca")).longValue();
            linha.put("situacao", diferenca == 0 ? "OK" : "DIVERGENTE");
            if (diferenca != 0) {
                log.warn("AUDITORIA ESTOQUE | empresa={} produto={} ({}) | sistema={} historico={} diferenca={}",
                        empresaId, linha.get("id"), linha.get("nome"),
                        linha.get("estoqueSistema"), linha.get("estoqueHistorico"), diferenca);
            }
        }
        return linhas;
    }

    /** Transações de estoque de um dia (padrão: hoje), da mais recente para a mais antiga. */
    @GetMapping("/movimentacoes")
    public List<Map<String, Object>> movimentacoes(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) String data) {
        int empresaId = user.empresa(authorization);
        LocalDate dia;
        try {
            dia = (data == null || data.isBlank()) ? InventarioService.hoje() : LocalDate.parse(data);
        } catch (RuntimeException erro) {
            throw new IllegalArgumentException("Data inválida. Use o formato AAAA-MM-DD.");
        }
        return db.queryForList("""
                SELECT m.id,
                       TO_CHAR(m.data::timestamptz AT TIME ZONE 'America/Fortaleza', 'DD/MM/YYYY HH24:MI:SS') AS "dataHora",
                       p.nome || CASE WHEN p.ativo THEN '' ELSE ' (excluído)' END AS "produto",
                       m.tipo,
                       m.quantidade,
                       m.motivo,
                       m.saldo_conferido AS "saldoConferido"
                  FROM movimentacoes_estoque m
                  JOIN produtos p ON p.id = m.produto_id
                 WHERE m.empresa_id = ?
                   AND (m.data::timestamptz AT TIME ZONE 'America/Fortaleza')::date = ?::date
              ORDER BY m.id DESC
                """, empresaId, dia.toString());
    }
}
