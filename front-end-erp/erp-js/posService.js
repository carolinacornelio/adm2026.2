import { apiService } from './apiService.js';
import { avisarMovimentacao } from './estoqueSync.js';
export const posService = {
    finalizar: async (venda) => {
        const resposta = await apiService.post('/pos/vendas', venda);
        avisarMovimentacao('venda-pos');
        return resposta;
    },
    listar: () => apiService.get('/pos/vendas'),
    buscar: (id) => apiService.get(`/pos/vendas/${encodeURIComponent(id)}`),
};
