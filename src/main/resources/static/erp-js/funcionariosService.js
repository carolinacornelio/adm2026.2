/** Funcionários, perfis de acesso e catálogo de permissões. A empresa é sempre a do token. */
import { apiService } from './apiService.js';
const qs = (p = {}) => { const s = new URLSearchParams(Object.entries(p).filter(([, v]) => v !== '' && v != null)).toString(); return s ? `?${s}` : ''; };
export const listarFuncionarios = (filtros) => apiService.get(`/funcionarios${qs(filtros)}`);
export const buscarFuncionario = (id) => apiService.get(`/funcionarios/${id}`);
export const criarFuncionario = (d) => apiService.post('/funcionarios', d);
export const atualizarFuncionario = (id, d) => apiService.put(`/funcionarios/${id}`, d);
export const alterarStatusFuncionario = (id, ativo) => apiService.put(`/funcionarios/${id}/status`, { ativo });
export const redefinirSenhaFuncionario = (id, senha) => apiService.post(`/funcionarios/${id}/senha`, senha ? { senha } : {});
export const alterarMinhaSenha = (senhaAtual, novaSenha) => apiService.post('/auth/alterar-senha', { senhaAtual, novaSenha });
export const listarPerfis = () => apiService.get('/perfis');
export const criarPerfil = (d) => apiService.post('/perfis', d);
export const atualizarPerfil = (id, d) => apiService.put(`/perfis/${id}`, d);
export const excluirPerfil = (id) => apiService.delete(`/perfis/${id}`);
export const listarPermissoes = () => apiService.get('/permissoes');
