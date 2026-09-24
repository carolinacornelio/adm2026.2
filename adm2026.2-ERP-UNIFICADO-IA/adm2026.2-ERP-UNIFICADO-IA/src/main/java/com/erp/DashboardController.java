package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Indicadores gerais e comparação mensal de faturamento do dashboard. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter ROTULO_MES = DateTimeFormatter.ofPattern("MMMM 'de' uuuu", PT_BR);

    private final JdbcTemplate db;
    private final CurrentUser user;
    private final PlanService plans;

    public DashboardController(JdbcTemplate db, CurrentUser user, PlanService plans) {
        this.db = db;
        this.user = user;
        this.plans = plans;
    }

    /** Totais usados nos quatro cartões principais do dashboard. */
    @GetMapping("/indicadores")
    public Map<String, Object> indicadores(@RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        return Map.of(
                "totalPessoas", count("SELECT COUNT(*) FROM clientes WHERE empresa_id=?", empresaId)
                        + count("SELECT COUNT(*) FROM fornecedores WHERE empresa_id=?", empresaId),
                "totalProdutos", count("SELECT COUNT(*) FROM produtos WHERE empresa_id=? AND ativo=true", empresaId),
                "totalCompras", count("SELECT COUNT(*) FROM compras WHERE empresa_id=?", empresaId),
                "totalVendas", count("SELECT COUNT(*) FROM vendas WHERE empresa_id=?", empresaId));
    }

    /**
     * Compara o faturamento do mês solicitado com o mês imediatamente anterior.
     * O filtro por empresa impede que vendas de outro tenant entrem no cálculo.
     */
    @GetMapping("/faturamento")
    public Map<String, Object> faturamento(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) String mesReferencia) {
        int empresaId = user.empresa(authorization);
        plans.exigir(empresaId, "PLUS");

        YearMonth mesAtual = lerMes(mesReferencia);
        YearMonth mesAnterior = mesAtual.minusMonths(1);
        double faturamentoAtual = totalDoMes(empresaId, mesAtual);
        double faturamentoAnterior = totalDoMes(empresaId, mesAnterior);
        double variacaoPercentual = faturamentoAnterior == 0
                ? (faturamentoAtual > 0 ? 100d : 0d)
                : ((faturamentoAtual - faturamentoAnterior) / faturamentoAnterior) * 100d;

        return Map.of(
                "mesAtual", mesAtual.toString(),
                "mesAtualLabel", rotulo(mesAtual),
                "faturamentoAtual", faturamentoAtual,
                "mesAnterior", mesAnterior.toString(),
                "mesAnteriorLabel", rotulo(mesAnterior),
                "faturamentoAnterior", faturamentoAnterior,
                "variacaoPercentual", variacaoPercentual);
    }

    private int count(String sql, int empresaId) {
        return db.queryForObject(sql, Integer.class, empresaId);
    }

    private double totalDoMes(int empresaId, YearMonth mes) {
        Timestamp inicio = Timestamp.valueOf(mes.atDay(1).atStartOfDay());
        Timestamp fim = Timestamp.valueOf(mes.plusMonths(1).atDay(1).atStartOfDay());
        Number total = db.queryForObject("""
                SELECT COALESCE(SUM(total), 0)
                  FROM vendas
                 WHERE empresa_id = ?
                   AND data >= ?
                   AND data < ?
                """, Number.class, empresaId, inicio, fim);
        return total == null ? 0d : total.doubleValue();
    }

    private YearMonth lerMes(String valor) {
        if (valor == null || valor.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(valor);
        } catch (RuntimeException erro) {
            throw new IllegalArgumentException("Mês de referência inválido. Use o formato AAAA-MM.");
        }
    }

    private String rotulo(YearMonth mes) {
        String texto = mes.format(ROTULO_MES);
        return texto.substring(0, 1).toUpperCase(PT_BR) + texto.substring(1);
    }
}
