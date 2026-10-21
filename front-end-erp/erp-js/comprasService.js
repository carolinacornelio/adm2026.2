/** Compras e entrada de estoque sao registradas em uma transacao no backend. */
import { apiService } from './apiService.js';
export async function listarCompras() { return apiService.get('/compras'); }
export async function criarCompra(compraDTO) { return apiService.post('/compras', compraDTO); }
