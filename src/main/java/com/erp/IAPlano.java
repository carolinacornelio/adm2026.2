package com.erp;

import java.util.List;

/** Plano estruturado que o backend valida antes de consultar o banco. */
public record IAPlano(
        boolean permitido,
        String motivo,
        List<IAConsulta> consultas,
        List<String> calculos
) {
    public IAPlano {
        consultas = consultas == null ? List.of() : List.copyOf(consultas);
        calculos = calculos == null ? List.of() : List.copyOf(calculos);
        motivo = motivo == null ? "" : motivo;
    }
}
