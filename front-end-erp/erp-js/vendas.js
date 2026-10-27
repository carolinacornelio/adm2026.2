/**
 * vendas.js
 * ---------------------------------------------------------------------------
 * Controller de vendas.html. Muito parecido com compras.js, com duas
 * diferenças importantes:
 *   - O preço unitário NÃO é digitado: vem automaticamente do preço de
 *     venda cadastrado do produto (célula somente leitura).
 *   - Antes de finalizar, valida se há estoque suficiente para cada item
 *     (a mesma validação é refeita em vendasService.js, pois nunca se deve
 *     confiar só na validação do front-end).
 * ---------------------------------------------------------------------------
 */

import { listarPessoas } from './pessoasService.js';
import { listarProdutos } from './produtosService.js';
import { criarVenda } from './vendasService.js';
import { estaAutenticado } from './authService.js';
import { fromPessoaDTO, fromProdutoDTO, toVendaDTO } from './dto.js';
import { iniciarCarregando, pararCarregando, mostrarErro, limparMensagem, escapeHtml } from './uiService.js';
import { hojeNoFusoERP } from './timeUtils.js';

let produtosDisponiveis = [];
let produtosPorId = new Map();
const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

function formatarMoeda(valor) {
    return moeda.format(Number(valor || 0));
}

function popularSelectClientes(selectElement, clientes) {
    const opcoes = clientes
        .map((c) => `<option value="${escapeHtml(c.id)}">${escapeHtml(c.nome)}</option>`)
        .join('');
    selectElement.insertAdjacentHTML('beforeend', opcoes);
}

function popularSelectProdutos(selectElement) {
    const opcoes = produtosDisponiveis
        .map(
            (p) =>
                `<option value="${escapeHtml(p.id)}">${escapeHtml(p.nome)} (${escapeHtml(p.codigo)}) — estoque: ${p.quantidadeEstoque}</option>`
        )
        .join('');
    selectElement.innerHTML = `<option value="">Selecione um produto</option>${opcoes}`;
}

/** Lê o preço unitário já exibido na linha (definido pelo produto escolhido). */
function precoUnitarioDaLinha(linha) {
    const produtoId = linha.querySelector('[name="produto"]').value;
    // O backend devolve id numérico, enquanto <select>.value sempre é texto.
    const produto = produtosPorId.get(String(produtoId));
    return produto ? produto.precoVenda : 0;
}

function recalcularSubtotalLinha(linha) {
    const quantidade = Number(linha.querySelector('[name="quantidade"]').value) || 0;
    const preco = precoUnitarioDaLinha(linha);
    const subtotal = quantidade * preco;
    linha.querySelector('.coluna-subtotal').textContent = formatarMoeda(subtotal);
    return subtotal;
}

function recalcularTotalGeral(tbody, elementoTotal) {
    let total = 0;
    tbody.querySelectorAll('tr').forEach((linha) => {
        total += recalcularSubtotalLinha(linha);
    });
    elementoTotal.textContent = formatarMoeda(total);
}

function adicionarLinha(tbody) {
    const ultimaLinha = tbody.querySelector('tr:last-child');
    const novaLinha = ultimaLinha.cloneNode(true);

    novaLinha.querySelector('[name="produto"]').value = '';
    novaLinha.querySelector('[name="quantidade"]').value = '';
    novaLinha.querySelector('.preco-unitario').textContent = formatarMoeda(0);
    novaLinha.querySelector('.coluna-subtotal').textContent = formatarMoeda(0);

    tbody.appendChild(novaLinha);
}

function removerLinha(tbody, linha, elementoTotal) {
    const linhas = tbody.querySelectorAll('tr');
    if (linhas.length === 1) {
        linha.querySelector('[name="produto"]').value = '';
        linha.querySelector('[name="quantidade"]').value = '';
        linha.querySelector('.preco-unitario').textContent = formatarMoeda(0);
    } else {
        linha.remove();
    }
    recalcularTotalGeral(tbody, elementoTotal);
}

function coletarItens(tbody) {
    const itens = [];
    tbody.querySelectorAll('tr').forEach((linha) => {
        const produtoId = linha.querySelector('[name="produto"]').value;
        const textoQuantidade = linha.querySelector('[name="quantidade"]').value;
        if (produtoId || textoQuantidade) {
            itens.push({ produtoId, quantidade: Number(textoQuantidade), precoUnitario: precoUnitarioDaLinha(linha) });
        }
    });
    return itens;
}

function validarVenda(clienteId, data, itens) {
    if (!clienteId) return 'Selecione um cliente.';
    if (!data) return 'Informe a data da venda.';
    if (itens.length === 0) return 'Adicione ao menos um produto com quantidade válida.';
    if (itens.length > 200) return 'Informe até 200 itens por venda.';

    // Validação "de conveniência" no front-end: avisa antes mesmo de enviar.
    // A validação que realmente vale é feita em vendasService.js.
    const quantidades = new Map();
    for (const item of itens) {
        const produto = produtosPorId.get(String(item.produtoId));
        if (!produto) return 'Selecione um produto válido para cada item preenchido.';
        if (!Number.isInteger(item.quantidade) || item.quantidade < 1 || item.quantidade > 1000000)
            return 'Informe quantidades inteiras entre 1 e 1.000.000.';
        const quantidade = (quantidades.get(item.produtoId) || 0) + item.quantidade;
        quantidades.set(item.produtoId, quantidade);
        if (quantidade > 1000000) return 'Quantidade máxima por produto excedida.';
        if (quantidade > produto.quantidadeEstoque) {
            return `Estoque insuficiente para "${produto.nome}" (disponível: ${produto.quantidadeEstoque}).`;
        }
    }
    return null;
}

async function inicializarTelaVendas(form) {
    const selectCliente = document.getElementById('selectCliente');
    const inputData = document.getElementById('inputDataVenda');
    const tbody = document.getElementById('corpoItensVenda');
    const elementoTotal = document.getElementById('totalVenda');
    const botaoAdicionar = document.getElementById('btnAdicionarItemVenda');
    const botaoFinalizar = document.getElementById('btnFinalizarVenda');

    inputData.value = hojeNoFusoERP();

    const [pessoas, produtos] = await Promise.all([listarPessoas(), listarProdutos()]);
    const clientes = pessoas.map(fromPessoaDTO).filter((p) => p.tipoTexto.includes('Cliente'));
    produtosDisponiveis = produtos.map(fromProdutoDTO);
    produtosPorId = new Map(produtosDisponiveis.map(p => [String(p.id), p]));

    popularSelectClientes(selectCliente, clientes);
    popularSelectProdutos(tbody.querySelector('[name="produto"]'));

    // Quando o produto de uma linha muda, busca o preço de venda automaticamente.
    tbody.addEventListener('change', (evento) => {
        if (evento.target.matches('[name="produto"]')) {
            const linha = evento.target.closest('tr');
            linha.querySelector('.preco-unitario').textContent = formatarMoeda(precoUnitarioDaLinha(linha));
            recalcularTotalGeral(tbody, elementoTotal);
        }
    });

    tbody.addEventListener('input', (evento) => {
        if (evento.target.matches('[name="quantidade"]')) {
            recalcularTotalGeral(tbody, elementoTotal);
        }
    });

    tbody.addEventListener('click', (evento) => {
        const botaoRemover = evento.target.closest('[data-remover-item]');
        if (!botaoRemover) return;
        removerLinha(tbody, botaoRemover.closest('tr'), elementoTotal);
    });

    botaoAdicionar.addEventListener('click', () => {
        adicionarLinha(tbody);
        popularSelectProdutos(tbody.querySelector('tr:last-child [name="produto"]'));
    });

    let registrando = false;
    botaoFinalizar.addEventListener('click', async () => {
        if (registrando) return;
        limparMensagem(form);

        const clienteId = selectCliente.value;
        const data = inputData.value;
        const itens = coletarItens(tbody);

        const erroValidacao = validarVenda(clienteId, data, itens);
        if (erroValidacao) {
            mostrarErro(form, erroValidacao);
            return;
        }

        registrando = true;
        iniciarCarregando(botaoFinalizar, 'Registrando...');
        try {
            const vendaDTO = toVendaDTO({ clienteId, data, itens });
            await criarVenda(vendaDTO);
            window.location.href = 'dashboard.html';
        } catch (erro) {
            mostrarErro(form, erro.message || 'Não foi possível registrar a venda.');
        } finally {
            registrando = false;
            pararCarregando(botaoFinalizar);
        }
    });
    form.addEventListener('submit', evento => { evento.preventDefault(); botaoFinalizar.click(); });
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    const form = document.getElementById('formVenda');
    if (!form) return;
    const botao = document.getElementById('btnFinalizarVenda');
    botao.disabled = true;
    inicializarTelaVendas(form)
        .then(() => { botao.disabled = false; })
        .catch(erro => mostrarErro(form, erro.message || 'Não foi possível carregar os produtos. Atualize a página.'));
});
