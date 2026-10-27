/** Vendas e baixa de estoque sao validadas e registradas no backend. */
import { apiService } from './apiService.js';
import { avisarMovimentacao } from './estoqueSync.js';
export async function listarVendas(filtros = {}) {
    const parametros = new URLSearchParams();
    for (const campo of ['data', 'inicio', 'fim']) {
        if (filtros[campo]) parametros.set(campo, filtros[campo]);
    }
    const consulta = parametros.toString();
    return apiService.get(`/vendas${consulta ? `?${consulta}` : ''}`);
}
export async function buscarVenda(id) { return apiService.get(`/vendas/${encodeURIComponent(id)}`); }
export async function criarVenda(vendaDTO) {
    const venda = await apiService.post('/vendas', vendaDTO);
    avisarMovimentacao('venda');
    return venda;
}
