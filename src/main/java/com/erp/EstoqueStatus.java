package com.erp;

import java.util.List;
import java.util.Map;

/** Regra comum para o estoque vendável exibido pelo ERP/POS e para os alertas. */
public final class EstoqueStatus {
    private EstoqueStatus() {}
    public static long limiteProximo(long minimo) {
        minimo = Math.max(0, minimo);
        return minimo + Math.max(1, (minimo + 4) / 5);
    }
    public static String situacao(long saldo, long minimo) {
        if (saldo <= 0) return "Esgotado";
        if (saldo < Math.max(0, minimo)) return "Abaixo do mínimo";
        if (saldo == Math.max(0, minimo)) return "No estoque mínimo";
        return saldo <= limiteProximo(minimo) ? "Próximo do mínimo" : "OK";
    }
    /** SQL equivalente para que filtros do banco usem exatamente os limiares da tela. */
    public static String expressaoSql(String saldo, String minimo) {
        return "CASE WHEN ("+saldo+")<=0 THEN 'Esgotado' WHEN ("+saldo+")<GREATEST(0,("+minimo+")) THEN 'Abaixo do mínimo' "
                +"WHEN ("+saldo+")=GREATEST(0,("+minimo+")) THEN 'No estoque mínimo' "
                +"WHEN ("+saldo+")<=GREATEST(0,("+minimo+"))+GREATEST(1,CEIL(GREATEST(0,("+minimo+"))*0.2)) THEN 'Próximo do mínimo' ELSE 'OK' END";
    }
    public static List<Map<String, Object>> aplicar(List<Map<String, Object>> produtos) {
        for (var produto : produtos) {
            long saldo = produto.get("quantidadeEstoque") instanceof Number n ? n.longValue() : 0;
            long minimo = produto.get("estoqueMinimo") instanceof Number n ? n.longValue() : 0;
            produto.put("situacaoEstoque", situacao(saldo, minimo));
            produto.put("limiteProximoMinimo", limiteProximo(minimo));
        }
        return produtos;
    }
}
