/**
 * produtosService.js
 * ---------------------------------------------------------------------------
 * Serviço de produtos.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js';
import { storageService } from './storageService.js';

const TABELA = 'produtos';

export async function listarProdutos(filtros = {}) {
    // ===== ETAPA SPRING BOOT =====
    // return apiService.get(`/produtos${montarQuery(filtros)}`);

    // ===== ETAPA ATUAL =====
    let produtos = storageService.listarPorUsuario(TABELA);

    if (filtros.categoriaId) {
        produtos = produtos.filter((produto) => String(produto.categoriaId || '') === String(filtros.categoriaId));
    }

    return produtos;
}

function montarQuery(filtros) {
    const params = new URLSearchParams();
    if (filtros.categoriaId) params.set('categoriaId', filtros.categoriaId);
    if (filtros.busca) params.set('busca', filtros.busca);
    return params.toString() ? `?${params.toString()}` : '';
}

export async function buscarProduto(id) {
    // ===== ETAPA SPRING BOOT =====
    // return apiService.get(`/produtos/${encodeURIComponent(id)}`);

    return storageService.buscarPorId(TABELA, id);
}

export async function criarProduto(produtoDTO) {
    // ===== ETAPA SPRING BOOT =====
    // return apiService.post('/produtos', produtoDTO);

    return storageService.criar(TABELA, produtoDTO);
}

export async function atualizarProduto(id, produtoDTO) {
    // ===== ETAPA SPRING BOOT =====
    // return apiService.put(`/produtos/${encodeURIComponent(id)}`, produtoDTO);

    return storageService.atualizar(TABELA, id, produtoDTO);
}

export async function removerProduto(id) {
    // ===== ETAPA SPRING BOOT =====
    // await apiService.delete(`/produtos/${encodeURIComponent(id)}`);
    // return true;

    return storageService.remover(TABELA, id);
}

export async function ajustarEstoque(produtoId, quantidadeDelta) {
    const produto = await buscarProduto(produtoId);
    if (!produto) {
        throw new Error('Produto não encontrado ao ajustar o estoque.');
    }

    const novaQuantidade = (produto.quantidadeEstoque || 0) + quantidadeDelta;
    return storageService.atualizar(TABELA, produtoId, { quantidadeEstoque: novaQuantidade });
}
