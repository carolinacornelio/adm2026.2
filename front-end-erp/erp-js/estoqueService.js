/** Situacao de estoque atualizada pela API. */
import { apiService } from './apiService.js';
import { fromProdutoDTO } from './dto.js';
export async function listarSituacaoEstoque() {
    const dados = await apiService.get('/estoque');
    return dados.map(fromProdutoDTO);
}
