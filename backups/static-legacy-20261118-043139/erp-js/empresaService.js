import { apiService } from './apiService.js';
export const buscarEmpresa = () => apiService.get('/empresa');
export const atualizarEmpresa = (dados) => apiService.put('/empresa', dados);
