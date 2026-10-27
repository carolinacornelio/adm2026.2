/** Controller da Central de Atendimento: abertura e priorização de chamados. */
import { listarChamados, criarChamado, atualizarSituacao } from './atendimentoService.js';
import { estaAutenticado } from './authService.js';
import { iniciarCarregando, pararCarregando, escapeHtml } from './uiService.js';
import { formatarData } from './timeUtils.js';

const NOMES = { critica: 'Crítica', alta: 'Alta', media: 'Média' };
const PESO = { critica: 3, alta: 2, media: 1 };
const COLUNAS = 7;

const $ = (id) => document.getElementById(id);
let chamados = [];
let chamadoSelecionado = null;

function lerFormulario(form) {
    const f = new FormData(form);
    return {
        cliente: (f.get('cliente') || '').trim(),
        titulo: (f.get('titulo') || '').trim(),
        descricao: (f.get('descricao') || '').trim(),
        severidade: f.get('severidade') || 'media',
    };
}

function atualizarPrevia(form) {
    const d = lerFormulario(form);
    $('previaTitulo').textContent = d.titulo || 'Título do chamado';
    $('previaCliente').textContent = d.cliente || '—';
    $('previaDescricao').textContent = d.descricao || 'A descrição aparece aqui.';
    const selo = $('previaSeveridade');
    selo.textContent = NOMES[d.severidade];
    selo.className = `selo ${d.severidade}`;
}

function mostrarMensagem(texto, ok = false) {
    const el = $('mensagemForm');
    el.textContent = texto;
    el.classList.toggle('ok', ok);
}

function renderizarResumo() {
    Object.keys(NOMES).forEach((sev) => {
        $(`total-${sev}`).textContent = chamados.filter((c) => c.severidade === sev).length;
    });
}

function criarLinha(c) {
    const data = c.criadoEm ? formatarData(c.criadoEm) : '—';
    return `
        <tr>
            <td>#${String(c.id).padStart(4, '0')}</td>
            <td>${escapeHtml(c.cliente)}</td>
            <td>${escapeHtml(c.titulo)}</td>
            <td><span class="selo ${escapeHtml(c.severidade)}">${escapeHtml(NOMES[c.severidade] || c.severidade)}</span></td>
            <td><span class="selo aberto">${escapeHtml(c.situacao)}</span></td>
            <td>${data}</td>
            <td><button class="botao secundario" type="button" data-chamado="${Number(c.id)}">Detalhes</button></td>
        </tr>`;
}

/** Mais graves primeiro; dentro da mesma severidade, os mais novos. */
function renderizarLista() {
    const termo = $('pesquisaChamado').value.trim().toLowerCase();
    const severidade = $('filtroSeveridade').value;
    const visiveis = chamados
        .filter((c) => !severidade || c.severidade === severidade)
        .filter((c) => `${c.id} ${c.cliente} ${c.titulo}`.toLowerCase().includes(termo))
        .sort((a, b) => (PESO[b.severidade] || 0) - (PESO[a.severidade] || 0) || b.id - a.id);

    $('listaChamados').innerHTML = visiveis.length
        ? visiveis.map(criarLinha).join('')
        : `<tr><td class="vazio" colspan="${COLUNAS}">Nenhum chamado encontrado.</td></tr>`;
}

async function recarregar() {
    chamados = await listarChamados();
    renderizarResumo();
    renderizarLista();
}

function configurarFormulario(form) {
    form.addEventListener('input', () => {
        atualizarPrevia(form);
        mostrarMensagem('');
        form.querySelectorAll('.invalido').forEach((el) => el.classList.remove('invalido'));
    });

    form.addEventListener('reset', () => setTimeout(() => atualizarPrevia(form)));

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const dados = lerFormulario(form);
        const faltando = ['cliente', 'titulo', 'descricao'].filter((c) => !dados[c]);

        if (faltando.length) {
            faltando.forEach((c) => form.elements[c].classList.add('invalido'));
            form.elements[faltando[0]].focus();
            mostrarMensagem('Preencha cliente, título e descrição para abrir o chamado.');
            return;
        }

        const botao = form.querySelector('[type="submit"]');
        iniciarCarregando(botao, 'Abrindo...');
        try {
            await criarChamado(dados);
            form.reset();
            mostrarMensagem('Chamado aberto com sucesso.', true);
            await recarregar();
        } catch (erro) {
            mostrarMensagem(erro.message || 'Não foi possível abrir o chamado.');
        } finally {
            pararCarregando(botao);
        }
    });

    atualizarPrevia(form);
}

document.addEventListener('DOMContentLoaded', async () => {
    if (!estaAutenticado()) return;

    configurarFormulario($('formChamado'));
    $('pesquisaChamado').addEventListener('input', renderizarLista);
    $('filtroSeveridade').addEventListener('change', renderizarLista);
    $('listaChamados').addEventListener('click', (evento) => {
        const botao = evento.target.closest('[data-chamado]');
        if (!botao) return;
        chamadoSelecionado = chamados.find(c => c.id === Number(botao.dataset.chamado));
        if (!chamadoSelecionado) return;
        $('tituloDetalheChamado').textContent = `#${chamadoSelecionado.id} — ${chamadoSelecionado.titulo}`;
        $('descricaoDetalheChamado').textContent = chamadoSelecionado.descricao;
        $('situacaoChamado').value = chamadoSelecionado.situacao;
        $('erroSituacao').textContent = '';
        $('detalheChamado').showModal();
    });
    $('fecharDetalhe').addEventListener('click', () => $('detalheChamado').close());
    $('formSituacaoChamado').addEventListener('submit', async evento => {
        evento.preventDefault();
        if (!chamadoSelecionado) return;
        const botao = evento.target.querySelector('[type="submit"]');
        iniciarCarregando(botao, 'Salvando...');
        try {
            await atualizarSituacao(chamadoSelecionado.id, $('situacaoChamado').value);
            await recarregar();
            $('detalheChamado').close();
        } catch (erro) {
            $('erroSituacao').textContent = erro.message || 'Não foi possível atualizar a situação.';
        } finally { pararCarregando(botao); }
    });

    try {
        await recarregar();
    } catch (erro) {
        $('listaChamados').innerHTML =
            `<tr><td class="vazio" colspan="${COLUNAS}">${escapeHtml(erro.message || 'Não foi possível carregar os chamados.')}</td></tr>`;
    }
});
