/** Controller da página do assistente: conecta formulário, exemplos e resposta. */
import { perguntarIA } from './iaService.js';
import { estaAutenticado } from './authService.js';
import { iniciarCarregando, pararCarregando } from './uiService.js';

function inicializar() {
    const form = document.getElementById('formIA');
    const campo = document.getElementById('perguntaIA');
    const resposta = document.getElementById('respostaIA');
    const botao = document.getElementById('btnPerguntarIA');

    if (!form || !campo || !resposta || !botao) return;

    form.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        resposta.textContent = '';
        iniciarCarregando(botao, 'Analisando...');

        try {
            const dados = await perguntarIA(campo.value);
            resposta.textContent = dados?.resposta || 'O assistente não retornou uma resposta.';
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
