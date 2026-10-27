/** Catalogo e estoque consultados diretamente na API do ERP. */
import { apiService } from './apiService.js';
import { avisarMovimentacao } from './estoqueSync.js';
export async function listarProdutos(filtros = {}) {
    const params = new URLSearchParams();
    if (filtros.categoriaId) params.set('categoriaId', filtros.categoriaId);
    const query = params.toString();
    return apiService.get(`/produtos${query ? `?${query}` : ''}`);
}
export async function buscarProduto(id) { return apiService.get(`/produtos/${encodeURIComponent(id)}`); }
export async function criarProduto(produtoDTO) {
    const produto = await apiService.post('/produtos', produtoDTO);
    avisarMovimentacao('produto');
    return produto;
}
export async function atualizarProduto(id, produtoDTO) {
    const produto = await apiService.put(`/produtos/${encodeURIComponent(id)}`, produtoDTO);
    avisarMovimentacao('produto');
    return produto;
}
export async function removerProduto(id) {
    await apiService.delete(`/produtos/${encodeURIComponent(id)}`);
    avisarMovimentacao('produto');
    return true;
}
