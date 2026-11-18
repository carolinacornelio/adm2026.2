/** Controller da página do assistente: conecta formulário, exemplos e histórico conversacional. */
import { perguntarIA } from './iaService.js';
import { estaAutenticado } from './authService.js';

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
    const exemplos = document.querySelectorAll('.exemplo-ia');
    let consultando = false;

    if (!form || !campo || !resposta || !botao) return;

    form.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        if (consultando) return;
        const pergunta = campo.value.trim();
        if (!pergunta) return;

        consultando = true;
        resposta.textContent = 'Buscando dados...';
        resposta.hidden = false;
        resposta.setAttribute('aria-busy', 'true');
        botao.disabled = true;
        campo.disabled = true;
        campo.readOnly = true;
        form.setAttribute('aria-busy', 'true');
        campo.setAttribute('aria-disabled', 'true');
        exemplos.forEach((exemplo) => { exemplo.disabled = true; });
        try {
            const dados = await perguntarIA(pergunta, historico);
            const textoResposta = dados?.resposta || 'O assistente não retornou uma resposta.';
            adicionarHistorico('user', pergunta);
            adicionarHistorico('assistant', textoResposta);
            resposta.textContent = textoResposta;
            campo.value = '';
        } catch (erro) {
            resposta.textContent = erro?.status === 0 && !String(erro?.message || '').includes('demorou')
                ? 'O servidor ERP não respondeu. Verifique se a aplicação Java está iniciada e tente novamente.'
                : (erro?.message || 'Não foi possível consultar o assistente.');
        } finally {
            resposta.setAttribute('aria-busy', 'false');
            resposta.hidden = false;
            botao.disabled = false;
            campo.readOnly = false;
            campo.disabled = false;
            form.removeAttribute('aria-busy');
            campo.removeAttribute('aria-disabled');
            exemplos.forEach((exemplo) => { exemplo.disabled = false; });
            consultando = false;
        }
    });

    exemplos.forEach((botaoExemplo) => {
        botaoExemplo.addEventListener('click', () => {
            if (consultando) return;
            // As sugestões são perguntas completas, sem depender da conversa anterior.
            historico = [];
            campo.value = botaoExemplo.dataset.pergunta || '';
            campo.focus();
        });
    });
}

document.addEventListener('DOMContentLoaded', () => {
    if (estaAutenticado()) inicializar();
});
