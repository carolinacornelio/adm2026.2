/**
 * estoque.js
 * ---------------------------------------------------------------------------
 * Controller da tela estoque.html: lista a situação de todos os produtos
 * (estoque atual x estoque mínimo) e permite pesquisar por nome.
 * ---------------------------------------------------------------------------
 */

import { listarSituacaoEstoque } from './estoqueService.js';
import { estaAutenticado } from './authService.js';
import { renderizarTabela, classeStatusEstoque, escapeHtml } from './uiService.js';

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

async function carregarEstoque(tbody, termoBusca = '') {
    try {
        const produtos = await listarSituacaoEstoque();
        const filtrados = termoBusca
            ? produtos.filter((p) => p.nome.toLowerCase().includes(termoBusca.toLowerCase()))
            : produtos;

        renderizarTabela(tbody, filtrados, criarLinhaEstoque, 'Nenhum produto cadastrado ainda.');
    } catch (erro) {
        console.error(erro);
        tbody.innerHTML = `<tr><td colspan="4">Não foi possível carregar o estoque.</td></tr>`;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    const tbody = document.getElementById('listaEstoque');
    if (!tbody) return;

    carregarEstoque(tbody);

    const campoPesquisa = document.getElementById('pesquisaEstoque');
    if (campoPesquisa) {
        campoPesquisa.addEventListener('input', (evento) => carregarEstoque(tbody, evento.target.value));
    }
});
