package com.erp;

/**
 * Operações de leitura permitidas ao assistente.
 * Não existem ferramentas de escrita.
 */
public enum IATool {
    VENDAS_RESUMO,
    VENDAS_HISTORICO_MENSAL,
    VENDAS_LISTA,
    VENDAS_PRODUTOS,
    PRODUTO_PRECO,
    PRODUTOS_MAIS_VENDIDOS,
    PRODUTOS_MENOS_VENDIDOS,
    PRODUTOS_SEM_VENDAS,

    COMPRAS_RESUMO,
    COMPRAS_LISTA,
    COMPRAS_PRODUTOS,

    ESTOQUE_BAIXO,
    ESTOQUE_LISTA,
    ALERTAS_OPERACIONAIS,
    INVENTARIO_LOTES,
    PRODUTOS_VALIDADE,
    INVENTARIO_FISICO_RESUMO,
    INVENTARIO_FISICO_ITENS,
    ATENDIMENTO_RESUMO,
    ATENDIMENTO_CHAMADOS,
    VENDA_COMPROVANTE,
    ERP_FUNCIONAMENTO,

    PRODUTOS,

    CLIENTES_RESUMO,
    CLIENTES_RANKING,
    FORNECEDORES_RESUMO;

    public java.util.List<String> situacoesPermitidas() {
        return switch (this) {
            case INVENTARIO_FISICO_RESUMO, INVENTARIO_FISICO_ITENS -> java.util.List.of("TODOS", "EM_CONTAGEM", "CONCLUIDO");
            case ATENDIMENTO_RESUMO, ATENDIMENTO_CHAMADOS -> java.util.List.of("TODOS", "ABERTO", "EM_ATENDIMENTO", "RESOLVIDO", "CANCELADO");
            default -> java.util.List.of("TODOS", "VENCIDO", "A_VENCER", "VENCIDOS_E_A_VENCER", "PENDENTE", "SEM_VALIDADE");
        };
    }

    public static java.util.List<String> situacoesPlanejaveis() {
        return java.util.Arrays.stream(values()).flatMap(tool -> tool.situacoesPermitidas().stream()).distinct().toList();
    }
}
