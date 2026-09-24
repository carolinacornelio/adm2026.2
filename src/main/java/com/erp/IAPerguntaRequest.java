package com.erp;

import java.util.List;

/**
 * Requisição da tela de IA.
 * O histórico é opcional para manter compatibilidade com o frontend atual.
 */
public record IAPerguntaRequest(
        String pergunta,
        List<IAHistoricoItem> historico
) {
    public IAPerguntaRequest {
        historico = historico == null ? List.of() : List.copyOf(historico);
    }
}
