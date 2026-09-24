package com.erp;

/**
 * Operações de leitura permitidas ao assistente.
 * Não existem ferramentas de escrita.
 */
public enum IATool {
    VENDAS_RESUMO,
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

    PRODUTOS,

    CLIENTES_RESUMO,
    CLIENTES_RANKING,
    FORNECEDORES_RESUMO
}
