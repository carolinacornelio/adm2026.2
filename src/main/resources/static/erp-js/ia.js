/** Controller da página do assistente com contexto de conversa. */
import { perguntarIA } from './iaService.js';
import { estaAutenticado } from './authService.js';
import { iniciarCarregando, pararCarregando } from './uiService.js';

const historico = [];

function inicializar() {
    const form = document.getElementById('formIA');
    const campo = document.getElementById('perguntaIA');
    const resposta = document.getElementById('respostaIA');
    const botao = document.getElementById('btnPerguntarIA');

    if (!form || !campo || !resposta || !botao) return;

    form.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        const pergunta = campo.value.trim();
        if (!pergunta) {
            resposta.textContent = 'Digite uma pergunta para o assistente.';
            campo.focus();
            return;
        }

        resposta.textContent = 'Analisando sua pergunta e consultando os dados necessários...';
        iniciarCarregando(botao, 'Analisando...');

        try {
            const dados = await perguntarIA(pergunta, historico);
            const texto = dados?.resposta || 'O assistente não retornou uma resposta.';
            resposta.textContent = texto;

            historico.push({ papel: 'usuario', conteudo: pergunta });
            historico.push({ papel: 'assistente', conteudo: texto });
            while (historico.length > 8) historico.shift();

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
