/** Serviço HTTP do cadastro de categorias. */
import { apiService } from './apiService.js';

export function listarCategorias() {
    return apiService.get('/categorias');
}

export function criarCategoria(nome) {
    return apiService.post('/categorias', { nome });
}

export function atualizarCategoria(id, nome) {
    return apiService.put(`/categorias/${encodeURIComponent(id)}`, { nome });
}

export function removerCategoria(id) {
    return apiService.delete(`/categorias/${encodeURIComponent(id)}`);
}
