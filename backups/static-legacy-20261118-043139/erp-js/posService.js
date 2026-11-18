import { apiService } from './apiService.js';
export const posService = {
    finalizar: (venda) => apiService.post('/pos/vendas', venda),
    listar: () => apiService.get('/pos/vendas'),
    buscar: (id) => apiService.get(`/pos/vendas/${encodeURIComponent(id)}`),
};
