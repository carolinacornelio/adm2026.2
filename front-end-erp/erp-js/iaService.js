/** Serviço da tela de IA: mantém a chamada HTTP fora do controller visual. */
import { apiService } from './apiService.js';

export async function perguntarIA(pergunta) {
    const perguntaLimpa = String(pergunta || '').trim();
    if (!perguntaLimpa) throw new Error('Digite uma pergunta para o assistente.');
    return apiService.post('/ia/perguntar', { pergunta: perguntaLimpa });
}
