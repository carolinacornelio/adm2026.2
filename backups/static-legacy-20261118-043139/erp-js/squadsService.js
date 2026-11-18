import { apiService } from './apiService.js';
export const listarSquads = (status) => apiService.get(`/squads${status ? `?status=${encodeURIComponent(status)}` : ''}`);
export const buscarSquad = (id) => apiService.get(`/squads/${id}`);
export const candidatosSquad = (id) => apiService.get(`/squads/${id}/candidatos`);
export const criarSquad = (d) => apiService.post('/squads', d);
export const atualizarSquad = (id, d) => apiService.put(`/squads/${id}`, d);
export const adicionarIntegrantes = (id, usuarios) => apiService.post(`/squads/${id}/integrantes`, { usuarios });
export const removerIntegrante = (id, usuarioId) => apiService.delete(`/squads/${id}/integrantes/${usuarioId}`);
export const definirLider = (id, usuarioId) => apiService.put(`/squads/${id}/lider`, { usuarioId });
