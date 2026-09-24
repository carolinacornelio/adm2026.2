/** Consulta a comparação mensal calculada no backend Spring Boot. */
import { apiService } from './apiService.js';

export function buscarFaturamentoMensal(mesReferencia) {
    const mes = String(mesReferencia || '').trim();
    const query = mes ? `?mesReferencia=${encodeURIComponent(mes)}` : '';
    return apiService.get(`/dashboard/faturamento${query}`);
}
