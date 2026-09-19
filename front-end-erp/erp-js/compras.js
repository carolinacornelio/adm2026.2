/**
 * compras.js
 * ---------------------------------------------------------------------------
 * Controller de compras.html. Permite escolher um fornecedor, adicionar
 * várias linhas de produto/quantidade, usa o preço cadastrado automaticamente
 * e calcula o total
 * e, ao finalizar, registrar a compra (o que também dá entrada no estoque —
 * ver comprasService.js).
 * ---------------------------------------------------------------------------
 */

import { listarPessoas } from './pessoasService.js';
import { listarProdutos } from './produtosService.js';
import { criarCompra } from './comprasService.js';
import { estaAutenticado } from './authService.js';
import { fromPessoaDTO, fromProdutoDTO, toCompraDTO } from './dto.js';
import { iniciarCarregando, pararCarregando, mostrarErro, limparMensagem, escapeHtml } from './uiService.js';

let produtosDisponiveis = []; // cache em memória, carregado uma vez ao abrir a tela

function formatarMoeda(valor) {
    return Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

/** Preenche um <select> com uma lista de pessoas (fornecedores). */
function popularSelectFornecedores(selectElement, fornecedores) {
    const opcoes = fornecedores
        .map((f) => `<option value="${escapeHtml(f.id)}">${escapeHtml(f.nome)}</option>`)
        .join('');
    selectElement.insertAdjacentHTML('beforeend', opcoes);
}

/** Preenche um <select name="produto"> (de uma linha da tabela) com os produtos cadastrados. */
function popularSelectProdutos(selectElement) {
    const opcoes = produtosDisponiveis
        .map((p) => `<option value="${escapeHtml(p.id)}">${escapeHtml(p.nome)} (${escapeHtml(p.codigo)})</option>`)
        .join('');
    selectElement.insertAdjacentHTML('beforeend', opcoes);
}

/** Recalcula o subtotal de UMA linha (quantidade × preço) e devolve o valor numérico. */
function recalcularSubtotalLinha(linha) {
    const quantidade = Number(linha.querySelector('[name="quantidade"]').value) || 0;
    const preco = Number(linha.querySelector('.preco-unitario').dataset.preco) || 0;
    const subtotal = quantidade * preco;
    linha.querySelector('.coluna-subtotal').textContent = formatarMoeda(subtotal);
    return subtotal;
}

/** Soma o subtotal de todas as linhas e atualiza o "Total" geral do formulário. */
function recalcularTotalGeral(tbody, elementoTotal) {
    let total = 0;
    tbody.querySelectorAll('tr').forEach((linha) => {
        total += recalcularSubtotalLinha(linha);
    });
    elementoTotal.textContent = formatarMoeda(total);
}

/** Cria uma nova linha de item clonando a última e limpando os valores preenchidos. */
function adicionarLinha(tbody) {
    const ultimaLinha = tbody.querySelector('tr:last-child');
    const novaLinha = ultimaLinha.cloneNode(true);

    novaLinha.querySelector('[name="produto"]').value = '';
    novaLinha.querySelector('[name="quantidade"]').value = '';
    novaLinha.querySelector('.preco-unitario').dataset.preco = '0';
    novaLinha.querySelector('.preco-unitario').textContent = formatarMoeda(0);
    novaLinha.querySelector('.coluna-subtotal').textContent = formatarMoeda(0);

    tbody.appendChild(novaLinha);
}

/** Remove uma linha; se for a única restante, apenas limpa os campos dela. */
function removerLinha(tbody, linha, elementoTotal) {
    const linhas = tbody.querySelectorAll('tr');
    if (linhas.length === 1) {
        linha.querySelector('[name="produto"]').value = '';
        linha.querySelector('[name="quantidade"]').value = '';
        linha.querySelector('.preco-unitario').dataset.preco = '0';
        linha.querySelector('.preco-unitario').textContent = formatarMoeda(0);
    } else {
        linha.remove();
    }
    recalcularTotalGeral(tbody, elementoTotal);
}

/** Lê todas as linhas preenchidas da tabela e monta a lista de itens para o DTO. */
function coletarItens(tbody) {
    const itens = [];
    tbody.querySelectorAll('tr').forEach((linha) => {
        const produtoId = linha.querySelector('[name="produto"]').value;
        const quantidade = Number(linha.querySelector('[name="quantidade"]').value);
        const precoUnitario = Number(linha.querySelector('.preco-unitario').dataset.preco);
        if (produtoId && quantidade > 0) {
            itens.push({ produtoId, quantidade, precoUnitario });
        }
    });
    return itens;
}

function validarCompra(fornecedorId, data, itens) {
    if (!fornecedorId) return 'Selecione um fornecedor.';
    if (!data) return 'Informe a data da compra.';
    if (itens.length === 0) return 'Adicione ao menos um produto com quantidade válida.';
    return null;
}

async function inicializarTelaCompras(form) {
    const selectFornecedor = document.getElementById('selectFornecedor');
    const inputData = document.getElementById('inputDataCompra');
    const tbody = document.getElementById('corpoItensCompra');
    const elementoTotal = document.getElementById('totalCompra');
    const botaoAdicionar = document.getElementById('btnAdicionarItemCompra');
    const botaoFinalizar = document.getElementById('btnFinalizarCompra');

    // Data de hoje já vem preenchida, por conveniência.
    inputData.value = new Date().toISOString().split('T')[0];

    // Carrega fornecedores (pessoas do tipo Fornecedor) e produtos cadastrados.
    const [pessoas, produtos] = await Promise.all([listarPessoas(), listarProdutos()]);
    const fornecedores = pessoas.map(fromPessoaDTO).filter((p) => p.tipoTexto.includes('Fornecedor'));
    produtosDisponiveis = produtos.map(fromProdutoDTO);

    popularSelectFornecedores(selectFornecedor, fornecedores);
    popularSelectProdutos(tbody.querySelector('[name="produto"]'));

    // Quando o produto de uma linha muda, sugere o preço de compra cadastrado.
    tbody.addEventListener('change', (evento) => {
        if (evento.target.matches('[name="produto"]')) {
            const linha = evento.target.closest('tr');
            // O backend envia id numérico, mas o valor do select é string.
            const produto = produtosDisponiveis.find(
                (p) => String(p.id) === String(evento.target.value)
            );
            const preco = Number(produto?.precoCompra) || 0;
            const campoPreco = linha.querySelector('.preco-unitario');
            campoPreco.dataset.preco = String(preco);
            campoPreco.textContent = formatarMoeda(preco);
            recalcularTotalGeral(tbody, elementoTotal);
        }
    });

    // Somente a quantidade é editável; o preço vem do produto escolhido.
    tbody.addEventListener('input', (evento) => {
        if (evento.target.matches('[name="quantidade"]')) {
            recalcularTotalGeral(tbody, elementoTotal);
        }
    });

    // Remover item (delegação: funciona também nas linhas adicionadas depois).
    tbody.addEventListener('click', (evento) => {
        const botaoRemover = evento.target.closest('[data-remover-item]');
        if (!botaoRemover) return;
        removerLinha(tbody, botaoRemover.closest('tr'), elementoTotal);
    });

    botaoAdicionar.addEventListener('click', () => {
        adicionarLinha(tbody);
        popularSelectProdutos(tbody.querySelector('tr:last-child [name="produto"]'));
    });

    botaoFinalizar.addEventListener('click', async () => {
        limparMensagem(form);

        const fornecedorId = selectFornecedor.value;
        const data = inputData.value;
        const itens = coletarItens(tbody);

        const erroValidacao = validarCompra(fornecedorId, data, itens);
        if (erroValidacao) {
            mostrarErro(form, erroValidacao);
            return;
        }

        iniciarCarregando(botaoFinalizar, 'Registrando...');
        try {
            const compraDTO = toCompraDTO({ fornecedorId, data, itens });
            await criarCompra(compraDTO);
            window.location.href = 'dashboard.html';
        } catch (erro) {
            mostrarErro(form, erro.message || 'Não foi possível registrar a compra.');
        } finally {
            pararCarregando(botaoFinalizar);
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    const form = document.getElementById('formCompra');
    if (form) inicializarTelaCompras(form);
});
