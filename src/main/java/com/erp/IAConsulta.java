package com.erp;

import java.time.LocalDate;
import java.util.List;

/** Consulta planejada pelo modelo e validada pelo backend. */
public record IAConsulta(
        IATool ferramenta,
        LocalDate inicio,
        LocalDate fim,
        List<String> produtos,
        Integer vendaId,
        Integer dias,
        String situacao
) {
    public IAConsulta {
        produtos = produtos == null ? List.of() : List.copyOf(produtos);
    }

    public IAConsulta(IATool ferramenta, LocalDate inicio, LocalDate fim) {
        this(ferramenta, inicio, fim, List.of());
    }

    public IAConsulta(IATool ferramenta, LocalDate inicio, LocalDate fim, List<String> produtos) {
        this(ferramenta, inicio, fim, produtos, null, null, null);
    }
}
