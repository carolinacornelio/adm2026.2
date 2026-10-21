/** Vendas e baixa de estoque sao validadas e registradas no backend. */
import { apiService } from './apiService.js';
export async function listarVendas(filtros = {}) {
    const parametros = new URLSearchParams();
    for (const campo of ['data', 'inicio', 'fim']) {
        if (filtros[campo]) parametros.set(campo, filtros[campo]);
    }
    const consulta = parametros.toString();
    return apiService.get(`/vendas${consulta ? `?${consulta}` : ''}`);
}
export async function buscarVenda(id) { return apiService.get(`/vendas/${encodeURIComponent(id)}`); }
export async function criarVenda(vendaDTO) { return apiService.post('/vendas', vendaDTO); }
