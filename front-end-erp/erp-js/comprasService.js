/** Compras e entrada de estoque sao registradas em uma transacao no backend. */
import { apiService } from './apiService.js';
import { avisarMovimentacao } from './estoqueSync.js';
export async function listarCompras() { return apiService.get('/compras'); }
export async function criarCompra(compraDTO) {
    const compra = await apiService.post('/compras', compraDTO);
    avisarMovimentacao('compra');
    return compra;
}
