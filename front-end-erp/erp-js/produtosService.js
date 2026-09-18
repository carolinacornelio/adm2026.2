/**
 * produtosService.js
 * ---------------------------------------------------------------------------
 * Regra de negócio do domínio "Produtos". Mesma estratégia de migração
 * O fluxo atual usa a API REST; o localStorage aparece apenas como histórico.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js'; // usado nos blocos "ETAPA 3" comentados abaixo
import { storageService } from './storageService.js';

const TABELA = 'produtos';

/** Lista todos os produtos do usuário logado. */
export async function listarProdutos(filtros = {}) {
    // ===== ETAPA 3 (Spring Boot) =====
    const params = new URLSearchParams();
    if (filtros.categoriaId) params.set('categoriaId', filtros.categoriaId);
    const query = params.toString();
    return apiService.get(`/produtos${query ? `?${query}` : ''}`);

    // ===== ETAPA 2 (localStorage) =====
    return storageService.listarPorUsuario(TABELA);
}

/** Busca um produto pelo id. */
export async function buscarProduto(id) {
    // ===== ETAPA 3 (Spring Boot) =====
    // encodeURIComponent(id): evita que o id quebre a URL da requisição.
    return apiService.get(`/produtos/${encodeURIComponent(id)}`);

    // ===== ETAPA 2 (localStorage) =====
    return storageService.buscarPorId(TABELA, id);
}

/** Cria um novo produto. @param {object} produtoDTO - ver dto.js -> toProdutoDTO */
export async function criarProduto(produtoDTO) {
    // ===== ETAPA 3 (Spring Boot) =====
    return apiService.post('/produtos', produtoDTO);

    // ===== ETAPA 2 (localStorage) =====
    return storageService.criar(TABELA, produtoDTO);
}

/** Atualiza um produto existente. */
export async function atualizarProduto(id, produtoDTO) {
    // ===== ETAPA 3 (Spring Boot) =====
    return apiService.put(`/produtos/${encodeURIComponent(id)}`, produtoDTO);

    // ===== ETAPA 2 (localStorage) =====
    return storageService.atualizar(TABELA, id, produtoDTO);
}

/** Remove um produto. */
export async function removerProduto(id) {
    // ===== ETAPA 3 (Spring Boot) =====
    await apiService.delete(`/produtos/${encodeURIComponent(id)}`);
    return true;

    // ===== ETAPA 2 (localStorage) =====
    return storageService.remover(TABELA, id);
}

/**
 * Ajusta a quantidade em estoque de um produto, somando (ou subtraindo,
 * se `quantidadeDelta` for negativo) ao valor atual. Usado por
 * comprasService (entrada) e vendasService (saída).
 */
export async function ajustarEstoque(produtoId, quantidadeDelta) {
    // ===== ETAPA 3 (Spring Boot) =====
    // O ideal é que este ajuste aconteça dentro da própria transação de
    // POST /compras ou POST /vendas, no backend, para evitar inconsistência
    // caso duas requisições cheguem ao mesmo tempo. Por isso, nesta etapa,
    // não existe uma chamada apiService equivalente e específica aqui.

    // ===== ETAPA 2 (localStorage) =====
    const produto = await buscarProduto(produtoId);
    if (!produto) {
        throw new Error('Produto não encontrado ao ajustar o estoque.');
    }
    const novaQuantidade = (produto.quantidadeEstoque || 0) + quantidadeDelta;
    return storageService.atualizar(TABELA, produtoId, { quantidadeEstoque: novaQuantidade });
}
