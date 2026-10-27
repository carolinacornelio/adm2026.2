package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Indicadores gerais e comparação mensal de faturamento do dashboard. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private static final ZoneId FUSO_ERP = ZoneId.of("America/Fortaleza");
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
        // Uma ida ao banco, com todos os totais obtidos da mesma fotografia dos dados.
        return db.queryForMap("""
                SELECT (SELECT COUNT(*) FROM clientes WHERE empresa_id=?)
                     + (SELECT COUNT(*) FROM fornecedores WHERE empresa_id=?) AS "totalPessoas",
                       (SELECT COUNT(*) FROM produtos WHERE empresa_id=? AND ativo=true) AS "totalProdutos",
                       (SELECT COUNT(*) FROM compras WHERE empresa_id=?) AS "totalCompras",
                       (SELECT COUNT(*) FROM vendas WHERE empresa_id=?) AS "totalVendas"
                """, empresaId, empresaId, empresaId, empresaId, empresaId);
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

    private double totalDoMes(int empresaId, YearMonth mes) {
        OffsetDateTime inicio = mes.atDay(1).atStartOfDay(FUSO_ERP).toOffsetDateTime();
        OffsetDateTime fim = mes.plusMonths(1).atDay(1).atStartOfDay(FUSO_ERP).toOffsetDateTime();
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
        if (valor == null || valor.isBlank()) return YearMonth.from(InventarioService.hoje());
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
