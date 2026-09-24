/** Controller da página do assistente: conecta formulário, exemplos e histórico conversacional. */
import { perguntarIA } from './iaService.js';
import { estaAutenticado } from './authService.js';
import { iniciarCarregando, pararCarregando } from './uiService.js';

let historico = [];

function adicionarHistorico(papel, conteudo) {
    const texto = String(conteudo || '').trim();
    if (!texto) return;
    historico.push({ papel, conteudo: texto });
    historico = historico.slice(-8);
}

function inicializar() {
    const form = document.getElementById('formIA');
    const campo = document.getElementById('perguntaIA');
    const resposta = document.getElementById('respostaIA');
    const botao = document.getElementById('btnPerguntarIA');

    if (!form || !campo || !resposta || !botao) return;

    form.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        const pergunta = campo.value.trim();
        if (!pergunta) return;

        resposta.textContent = '';
        iniciarCarregando(botao, 'Analisando...');

        try {
            const dados = await perguntarIA(pergunta, historico);
            const textoResposta = dados?.resposta || 'O assistente não retornou uma resposta.';
            adicionarHistorico('user', pergunta);
            adicionarHistorico('assistant', textoResposta);
            resposta.textContent = textoResposta;
            campo.value = '';
        } catch (erro) {
            resposta.textContent = erro?.message || 'Não foi possível consultar o assistente.';
        } finally {
            pararCarregando(botao);
        }
    });

    document.querySelectorAll('.exemplo-ia').forEach((botaoExemplo) => {
        botaoExemplo.addEventListener('click', () => {
            campo.value = botaoExemplo.dataset.pergunta || '';
            campo.focus();
        });
    });
}

document.addEventListener('DOMContentLoaded', () => {
    if (estaAutenticado()) inicializar();
});
