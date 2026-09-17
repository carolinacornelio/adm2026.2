/**
 * produtos.js
 * ---------------------------------------------------------------------------
 * Controller de cadastro/listagem de produtos.
 * Categorias e filtros são recursos do plano PLUS/PREMIUM.
 * ---------------------------------------------------------------------------
 */

import { listarProdutos, criarProduto, removerProduto } from '../../.github/modernize/java-upgrade/hooks/scripts/atu/atualização-erp/produtosService.js';
import { listarCategorias } from './categoriasService.js';
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

let categoriasDisponiveis = [];

function formatarMoeda(valor) {
    return Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function validarProduto(dados) {
    if (!dados.nome?.trim()) return 'Informe o nome do produto.';
    if (!dados.codigo?.trim()) return 'Informe o código do produto.';
    if (dados.precoVenda === '' || Number(dados.precoVenda) < 0) {
        return 'Informe um preço de venda válido.';
    }
    return null;
}

async function carregarCategorias(selectElement) {
    if (!selectElement) return;

    try {
        categoriasDisponiveis = await listarCategorias();
        selectElement.innerHTML = '<option value="">Selecione uma categoria</option>' +
            categoriasDisponiveis
                .map((categoria) => `<option value="${escapeHtml(categoria.id)}">${escapeHtml(categoria.nome)}</option>`)
                .join('');
    } catch (erro) {
        console.error('Não foi possível carregar categorias:', erro);
        selectElement.innerHTML = '<option value="">Categorias indisponíveis</option>';
    }
}

function configurarFormularioCadastro(formProduto) {
    const botaoCadastrar = formProduto.querySelector('button.primario');
    const selectCategoria = formProduto.querySelector('[name="categoriaId"]');

    carregarCategorias(selectCategoria);

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

function criarLinhaProduto(produto) {
    const categoria = produto.categoriaNome || 'Sem categoria';

    return `
        <tr>
            <td>${escapeHtml(produto.codigo)}</td>
            <td>${escapeHtml(produto.nome)}</td>
            <td>${escapeHtml(categoria)}</td>
            <td>${formatarMoeda(produto.precoVenda)}</td>
            <td>${escapeHtml(produto.quantidadeEstoque)}</td>
            <td><span class="status ${classeStatusEstoque(produto.situacao)}">${escapeHtml(produto.situacao)}</span></td>
            <td>
                <div class="acoes-tabela">
                    <button class="acao-mini excluir" data-excluir="${escapeHtml(produto.id)}" type="button">Excluir</button>
                </div>
            </td>
        </tr>
    `;
}

async function carregarListagemProdutos(tbody, termoBusca = '', categoriaId = '') {
    try {
        const produtos = (await listarProdutos({ categoriaId })).map(fromProdutoDTO);

        const termo = termoBusca.trim().toLowerCase();

        const filtrados = produtos.filter((produto) => {
            const correspondeBusca =
                !termo ||
                produto.nome.toLowerCase().includes(termo) ||
                String(produto.codigo || '').toLowerCase().includes(termo);

            const correspondeCategoria =
                !categoriaId || String(produto.categoriaId || '') === String(categoriaId);

            return correspondeBusca && correspondeCategoria;
        });

        // Associa o nome da categoria aos produtos.
        filtrados.forEach((produto) => {
            const categoria = categoriasDisponiveis.find(
                (item) => String(item.id) === String(produto.categoriaId)
            );
            produto.categoriaNome = categoria?.nome || produto.categoriaNome || '';
        });

        renderizarTabela(tbody, filtrados, criarLinhaProduto, 'Nenhum produto encontrado com esses filtros.');
    } catch (erro) {
        console.error(erro);
        tbody.innerHTML = '<tr><td colspan="7">Não foi possível carregar a lista de produtos.</td></tr>';
    }
}

async function configurarListagem(tbody) {
    const campoPesquisa = document.getElementById('pesquisaProdutos');
    const filtroCategoria = document.getElementById('filtroCategoria');

    await carregarCategorias(filtroCategoria);
    await carregarListagemProdutos(tbody);

    const recarregar = () => carregarListagemProdutos(
        tbody,
        campoPesquisa?.value || '',
        filtroCategoria?.value || ''
    );

    campoPesquisa?.addEventListener('input', recarregar);
    filtroCategoria?.addEventListener('change', recarregar);

    tbody.addEventListener('click', async (evento) => {
        const botao = evento.target.closest('[data-excluir]');
        if (!botao) return;

        const confirmou = window.confirm('Deseja realmente excluir este produto?');
        if (!confirmou) return;

        try {
            await removerProduto(botao.dataset.excluir);
            await recarregar();
        } catch (erro) {
            window.alert(erro.message || 'Não foi possível excluir o produto.');
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    const formProduto = document.getElementById('formProduto');
    if (formProduto) configurarFormularioCadastro(formProduto);

    const listaProdutos = document.getElementById('listaProdutos');
    if (listaProdutos) configurarListagem(listaProdutos);
});
