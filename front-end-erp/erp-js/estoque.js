/**
 * estoque.js
 * ---------------------------------------------------------------------------
 * Controller da tela estoque.html: lista a situação de todos os produtos
 * (estoque atual x estoque mínimo) e permite pesquisar por nome.
 *
 * Regra de atualização: a lista é recarregada ao abrir a página, ao voltar
 * para a aba, a cada INTERVALO_MS enquanto a aba estiver visível e quando o
 * usuário clica em "Atualizar". A pesquisa filtra a lista já carregada.
 * ---------------------------------------------------------------------------
 */

import { listarSituacaoEstoque } from './estoqueService.js';
import { estaAutenticado } from './authService.js';
import { renderizarTabela, classeStatusEstoque, escapeHtml } from './uiService.js';

const INTERVALO_MS = 30000;

let produtos = [];
let carregando = false;

function criarLinhaEstoque(produto) {
    return `
        <tr>
            <td>${escapeHtml(produto.nome)}</td>
            <td>${produto.quantidadeEstoque}</td>
            <td>${produto.estoqueMinimo}</td>
            <td><span class="status ${classeStatusEstoque(produto.situacao)}">${produto.situacao}</span></td>
        </tr>
    `;
}

function desenhar(tbody, termoBusca = '') {
    const termo = termoBusca.trim().toLowerCase();
    const filtrados = termo
        ? produtos.filter((p) => p.nome.toLowerCase().includes(termo))
        : produtos;

    renderizarTabela(tbody, filtrados, criarLinhaEstoque, 'Nenhum produto cadastrado ainda.');
}

async function atualizar(tbody, campoPesquisa, rotulo) {
    if (carregando) return;
    carregando = true;
    try {
        produtos = await listarSituacaoEstoque();
        desenhar(tbody, campoPesquisa?.value || '');
        if (rotulo) rotulo.textContent = `Atualizado às ${new Date().toLocaleTimeString('pt-BR')}`;
    } catch (erro) {
        console.error(erro);
        if (!produtos.length) {
            tbody.innerHTML = `<tr><td colspan="4">Não foi possível carregar o estoque.</td></tr>`;
        } else if (rotulo) {
            rotulo.textContent = 'Falha ao atualizar; exibindo os últimos dados carregados.';
        }
    } finally {
        carregando = false;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    const tbody = document.getElementById('listaEstoque');
    if (!tbody) return;

    const campoPesquisa = document.getElementById('pesquisaEstoque');
    const botaoAtualizar = document.getElementById('atualizarEstoque');
    const rotulo = document.getElementById('ultimaAtualizacao');
    const recarregar = () => atualizar(tbody, campoPesquisa, rotulo);

    recarregar();

    campoPesquisa?.addEventListener('input', () => desenhar(tbody, campoPesquisa.value));
    botaoAtualizar?.addEventListener('click', recarregar);

    // Voltar para a aba do estoque mostra o dado novo na hora (o cenário do teste de duas abas).
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') recarregar();
    });

    // Atualização periódica enquanto a aba estiver visível.
    setInterval(() => {
        if (document.visibilityState === 'visible') recarregar();
    }, INTERVALO_MS);
});
