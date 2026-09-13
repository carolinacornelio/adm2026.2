/**
 * produtos.js
 * ---------------------------------------------------------------------------
 * Controller do domínio "Produtos". Assim como pessoas.js, um único arquivo
 * cuida das duas telas (produtos-cadastro.html e produtos-listagem.html).
 * ---------------------------------------------------------------------------
 */

import { listarProdutos, criarProduto, removerProduto } from './produtosService.js';
import { estaAutenticado } from './authService.js';
import { toProdutoDTO, fromProdutoDTO } from './dto.js';
import {
    formParaObjeto,
    iniciarCarregando,
    pararCarregando,
    mostrarErro,
    limparMensagem,
    renderizarTabela,
    tratarErro,
    classeStatusEstoque,
    escapeHtml,
} from './uiService.js';

// ---------------------------------------------------------------------------
// Tela: produtos-cadastro.html
// ---------------------------------------------------------------------------

function validarProduto(dados) {
    if (!dados.nome?.trim()) return 'Informe o nome do produto.';
    if (!dados.codigo?.trim()) return 'Informe o código do produto.';
    if (dados.precoVenda === '' || Number(dados.precoVenda) < 0) {
        return 'Informe um preço de venda válido.';
    }
    return null;
}

function configurarFormularioCadastro(formProduto) {
    const botaoCadastrar = formProduto.querySelector('button.primario');

    botaoCadastrar.addEventListener('click', async () => {
        limparMensagem(formProduto);
        const dados = formParaObjeto(formProduto);

        const erroValidacao = validarProduto(dados);
        if (erroValidacao) {
            mostrarErro(formProduto, erroValidacao);
            return;
        }

        iniciarCarregando(botaoCadastrar, 'Cadastrando...');
        try {
            const produtoDTO = toProdutoDTO(dados);
            await criarProduto(produtoDTO);
            window.location.href = 'produtos-listagem.html';
        } catch (erro) {
            tratarErro(formProduto, erro);
        } finally {
            pararCarregando(botaoCadastrar);
        }
    });
}

// ---------------------------------------------------------------------------
// Tela: produtos-listagem.html
// ---------------------------------------------------------------------------

function formatarMoeda(valor) {
    return Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function criarLinhaProduto(produto) {
    return `
        <tr>
            <td>${escapeHtml(produto.codigo)}</td>
            <td>${escapeHtml(produto.nome)}</td>
            <td>${formatarMoeda(produto.precoVenda)}</td>
            <td>${produto.quantidadeEstoque}</td>
            <td><span class="status ${classeStatusEstoque(produto.situacao)}">${produto.situacao}</span></td>
            <td>
                <div class="acoes-tabela">
                    <button class="acao-mini excluir" data-excluir="${escapeHtml(produto.id)}" type="button">Excluir</button>
                </div>
            </td>
        </tr>
    `;
}

async function carregarListagemProdutos(tbody, termoBusca = '') {
    try {
        const produtos = (await listarProdutos()).map(fromProdutoDTO);
        const filtrados = termoBusca
            ? produtos.filter((p) => p.nome.toLowerCase().includes(termoBusca.toLowerCase()))
            : produtos;

        renderizarTabela(tbody, filtrados, criarLinhaProduto, 'Nenhum produto cadastrado ainda.');
    } catch (erro) {
        console.error(erro);
        tbody.innerHTML = `<tr><td colspan="6">Não foi possível carregar a lista de produtos.</td></tr>`;
    }
}

function configurarListagem(tbody) {
    carregarListagemProdutos(tbody);

    const campoPesquisa = document.getElementById('pesquisaProdutos');
    if (campoPesquisa) {
        campoPesquisa.addEventListener('input', (evento) => {
            carregarListagemProdutos(tbody, evento.target.value);
        });
    }

    tbody.addEventListener('click', async (evento) => {
        const botao = evento.target.closest('[data-excluir]');
        if (!botao) return;

        const confirmou = window.confirm('Deseja realmente excluir este produto?');
        if (!confirmou) return;

        try {
            await removerProduto(botao.dataset.excluir);
            carregarListagemProdutos(tbody, campoPesquisa?.value || '');
        } catch (erro) {
            window.alert(erro.message || 'Não foi possível excluir este produto.');
        }
    });
}

// ---------------------------------------------------------------------------
// Inicialização
// ---------------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    const formProduto = document.getElementById('formProduto');
    if (formProduto) configurarFormularioCadastro(formProduto);

    const listaProdutos = document.getElementById('listaProdutos');
    if (listaProdutos) configurarListagem(listaProdutos);
});
