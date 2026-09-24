/**
 * iaService.js
 * ---------------------------------------------------------------------------
 * Assistente inteligente do ERP.
 *
 * ENDPOINT SPRING:
 * POST /api/ia/perguntar
 *
 * Request:
 * {
 *   "pergunta": "Quais produtos estão vendendo menos?",
 *   "contexto": { ...dados selecionados pelo backend... }
 * }
 *
 * Na arquitetura final, o backend deve decidir quais dados do ERP são
 * relevantes para a pergunta. O navegador NÃO deve enviar o banco inteiro
 * para a IA e a chave da API do provedor de IA nunca deve ficar no JavaScript.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js';
import { storageService } from './storageService.js';

export async function perguntarIA(pergunta) {
    const perguntaLimpa = String(pergunta || '').trim();

    if (!perguntaLimpa) {
        throw new Error('Digite uma pergunta para o assistente.');
    }

    // ===== ETAPA SPRING BOOT =====
    // return apiService.post('/ia/perguntar', { pergunta: perguntaLimpa });

    // ===== ETAPA ATUAL / DEMONSTRAÇÃO =====
    // Esta resposta é somente para testar a interface antes do backend.
    const produtos = storageService.listarPorUsuario('produtos');
    const vendas = storageService.listarPorUsuario('vendas');

    if (produtos.length === 0 && vendas.length === 0) {
        return {
            resposta:
                'Ainda não há dados suficientes no ERP para fazer uma análise. ' +
                'Quando o backend estiver conectado, o assistente consultará os dados relevantes automaticamente.',
        };
    }

    return {
        resposta:
            'O assistente já está preparado no front-end. A pergunta foi recebida, ' +
            'mas a análise real dos dados será feita pelo Spring Boot quando o endpoint /api/ia/perguntar estiver conectado.',
    };
}
