package com.erp;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** A validade de hoje ainda permite venda. Datas usam o dia comercial de Fortaleza. */
public final class ValidadeStatus {
    private ValidadeStatus() {}
    public static String nivelAlerta(long dias) {
        return dias <= 3 ? "PRIORIDADE" : dias <= 15 ? "IMPORTANTE" : "NORMAL";
    }
    public static void aplicar(Map<String,Object> lote, LocalDate hoje, int diasAlerta) {
        Object valor = lote.get("validade");
        LocalDate validade = valor == null ? null : LocalDate.parse(valor.toString().substring(0,10));
        boolean obrigatoria = Boolean.TRUE.equals(lote.get("controlaValidade"));
        Long dias = validade == null ? null : ChronoUnit.DAYS.between(hoje, validade);
        String situacao = dias == null ? (obrigatoria ? "Validade pendente" : "Sem validade")
                : dias < 0 ? "Vencido" : dias <= diasAlerta ? "A vencer" : "Regular";
        String nivel = "Vencido".equals(situacao) || "Validade pendente".equals(situacao) ? "BLOQUEADO"
                : "A vencer".equals(situacao) ? nivelAlerta(dias) : "REGULAR";
        lote.put("situacao", situacao);
        lote.put("nivelValidade", nivel);
        lote.put("diasParaVencer", dias);
        lote.put("vendavel", !"BLOQUEADO".equals(nivel));
    }
}
