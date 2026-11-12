/** Catalogo e estoque consultados diretamente na API do ERP. */
import { apiService } from './apiService.js';
export async function listarProdutos(filtros = {}) {
    const params = new URLSearchParams();
    if (filtros.categoriaId) params.set('categoriaId', filtros.categoriaId);
    const query = params.toString();
    return apiService.get(`/produtos${query ? `?${query}` : ''}`);
}
export async function buscarProduto(id) { return apiService.get(`/produtos/${encodeURIComponent(id)}`); }
export async function criarProduto(produtoDTO) { return apiService.post('/produtos', produtoDTO); }
export async function atualizarProduto(id, produtoDTO) { return apiService.put(`/produtos/${encodeURIComponent(id)}`, produtoDTO); }
export async function removerProduto(id) {
    await apiService.delete(`/produtos/${encodeURIComponent(id)}`);
    return true;
}

export const produtosService = {
    listarProdutos,
    buscarProduto,
    criarProduto,
    atualizarProduto,
    removerProduto,
};

