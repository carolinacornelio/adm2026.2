/** Serviço da tela de IA. */
import { apiService } from './apiService.js';

export async function perguntarIA(pergunta, historico = []) {
    const perguntaLimpa = String(pergunta || '').trim();
    if (!perguntaLimpa) throw new Error('Digite uma pergunta para o assistente.');

    return apiService.post('/ia/perguntar', {
        pergunta: perguntaLimpa,
        historico: Array.isArray(historico) ? historico.slice(-8) : []
    });
}
