import { apiService } from './apiService.js';
export const buscarIndicadoresEstrategicos = () => apiService.get('/dashboard/estrategico');
