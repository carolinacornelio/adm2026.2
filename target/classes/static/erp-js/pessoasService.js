/** Cadastro de pessoas pela API autenticada do ERP. */
import { apiService } from './apiService.js';
export async function listarPessoas() { return apiService.get('/pessoas'); }
export async function buscarPessoa(id, tipo) { return apiService.get(`/pessoas/${encodeURIComponent(id)}${tipo ? '?tipo=' + encodeURIComponent(tipo) : ''}`); }
export async function criarPessoa(pessoaDTO) { return apiService.post('/pessoas', pessoaDTO); }
export async function atualizarPessoa(id, pessoaDTO) { return apiService.put(`/pessoas/${encodeURIComponent(id)}`, pessoaDTO); }
export async function removerPessoa(id, tipo = 'CLIENTE') {
    await apiService.delete(`/pessoas/${encodeURIComponent(id)}?tipo=${encodeURIComponent(tipo)}`);
    return true;
}
