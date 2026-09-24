/**
 * estoqueService.js
 * ---------------------------------------------------------------------------
 * A tela de estoque não tem um cadastro próprio: ela é uma "visão" sobre os
 * produtos já cadastrados (quantidade atual x estoque mínimo). Por isso este
 * service reaproveita `produtosService`, apenas aplicando o mapeamento
 * `fromProdutoDTO`, que já calcula o campo `situacao` (OK / Baixo / Esgotado).
 *
 * Na Etapa 3, o Spring pode expor um endpoint dedicado (GET /api/estoque)
 * que já devolve esse cálculo pronto do lado do servidor — a chamada
 * comentada abaixo mostra como ficaria.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js'; // usado nos blocos "ETAPA 3" comentados abaixo
import { listarProdutos } from './produtosService.js';
import { fromProdutoDTO } from './dto.js';

export async function listarSituacaoEstoque() {
    // ===== ETAPA 3 (Spring Boot) =====
    const dados = await apiService.get('/estoque');
    return dados.map(fromProdutoDTO);

    // ===== ETAPA 2 (localStorage) =====
    const produtos = await listarProdutos();
    return produtos.map(fromProdutoDTO);
}
