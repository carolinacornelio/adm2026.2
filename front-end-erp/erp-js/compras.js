/** Compras: linhas independentes, preço cadastrado e validação de lote/validade. */
import { listarPessoas } from './pessoasService.js';
import { listarProdutos } from './produtosService.js';
import { criarCompra } from './comprasService.js';
import { estaAutenticado } from './authService.js';
import { fromPessoaDTO, fromProdutoDTO, toCompraDTO } from './dto.js';
import { iniciarCarregando, pararCarregando, mostrarErro, limparMensagem, escapeHtml } from './uiService.js';
import { hojeNoFusoERP } from './timeUtils.js';
import { complianceService } from './complianceService.js';

let produtosPorId = new Map();
let modeloLinha;
const formatadorMoeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const moeda = valor => formatadorMoeda.format(Number(valor) || 0);

/** Campos opcionais continuam disponíveis para preservar a rastreabilidade por lote. */
function aplicarControleValidade(linha, produto) {
    const exige = Boolean(produto?.controlaValidade);
    const lote = linha.querySelector('[name="lote"]');
    const validade = linha.querySelector('[name="validade"]');
    lote.required = validade.required = exige;
    lote.placeholder = exige ? 'Lote obrigatório' : 'Lote opcional';
    validade.min = hojeNoFusoERP();
    validade.setAttribute('aria-label', exige ? 'Validade obrigatória' : 'Validade opcional');
}

function recalcularLinha(linha) {
    const quantidade = Number(linha.querySelector('[name="quantidade"]').value) || 0;
    const preco = Number(linha.querySelector('.preco-unitario').dataset.preco) || 0;
    const subtotal = Math.round(quantidade * preco * 100) / 100;
    linha.dataset.subtotal = String(subtotal);
    linha.querySelector('.coluna-subtotal').textContent = moeda(subtotal);
}

function atualizarTotal(tbody, elementoTotal) {
    const total = [...tbody.rows].reduce((soma, linha) => soma + (Number(linha.dataset.subtotal) || 0), 0);
    elementoTotal.textContent = moeda(total);
}

function adicionarLinha(tbody) {
    const linha = modeloLinha.cloneNode(true);
    aplicarControleValidade(linha, null);
    tbody.appendChild(linha);
    return linha;
}

function removerLinha(tbody, linha, elementoTotal) {
    if (tbody.rows.length === 1) {
        const limpa = modeloLinha.cloneNode(true);
        aplicarControleValidade(limpa, null);
        linha.replaceWith(limpa);
    } else linha.remove();
    atualizarTotal(tbody, elementoTotal);
}

function coletarItens(tbody) {
    return [...tbody.rows].map(linha => ({
        produtoId: linha.querySelector('[name="produto"]').value,
        quantidadeTexto: linha.querySelector('[name="quantidade"]').value,
        precoUnitario: Number(linha.querySelector('.preco-unitario').dataset.preco),
        lote: linha.querySelector('[name="lote"]').value.trim(),
        validade: linha.querySelector('[name="validade"]').value,
    })).filter(item => item.produtoId || item.quantidadeTexto || item.lote || item.validade)
        .map(({ quantidadeTexto, ...item }) => ({ ...item, quantidade: Number(quantidadeTexto) }));
}

function validarCompra(fornecedorId, data, itens) {
    if (!fornecedorId) return 'Selecione um fornecedor.';
    if (!data) return 'Informe a data da compra.';
    if (!itens.length) return 'Adicione ao menos um produto com quantidade válida.';
    if (itens.length > 200) return 'Informe até 200 itens por compra.';
    const hoje = hojeNoFusoERP();
    for (const item of itens) {
        const produto = produtosPorId.get(String(item.produtoId));
        if (!produto) return 'Selecione um produto válido para cada item preenchido.';
        if (!Number.isInteger(item.quantidade) || item.quantidade < 1 || item.quantidade > 1000000)
            return 'Informe quantidades inteiras entre 1 e 1.000.000.';
        if (produto.controlaValidade && (!item.lote || !item.validade)) return `Informe lote e validade para ${produto.nome}.`;
        if (item.validade && item.validade < hoje) return `A validade de ${produto.nome} já venceu. Confira o lote.`;
    }
    return null;
}

async function inicializarTelaCompras(form) {
    const selectFornecedor = document.getElementById('selectFornecedor');
    const inputData = document.getElementById('inputDataCompra');
    const tbody = document.getElementById('corpoItensCompra');
    const elementoTotal = document.getElementById('totalCompra');
    const botaoAdicionar = document.getElementById('btnAdicionarItemCompra');
    const botaoFinalizar = document.getElementById('btnFinalizarCompra');
    const tipoDocumento = document.getElementById('tipoDocumentoCompra');
    const selectContrato = document.getElementById('contratoCompra');
    const notaFiscal = document.getElementById('notaFiscalCompra');
    const valorServico = document.getElementById('valorServicoCompra');
    const descricaoServico = document.getElementById('descricaoServicoCompra');
    let contratos = [];
    inputData.value = hojeNoFusoERP();

    const [pessoas, produtos] = await Promise.all([listarPessoas(), listarProdutos()]);
    const fornecedores = pessoas.map(fromPessoaDTO).filter(p => p.tipoTexto.includes('Fornecedor'));
    const catalogo = produtos.map(fromProdutoDTO);
    try { contratos = await complianceService.contratos(); }
    catch (erro) { mostrarErro(form, 'Não foi possível consultar os contratos. Atualize a página antes de registrar serviços.'); }
    function preencherContratos() {
        const anterior = selectContrato.value;
        selectContrato.innerHTML = '<option value="">' + (tipoDocumento.value === 'SERVICO' ? 'Selecione um contrato aprovado' : 'Sem contrato') + '</option>' + contratos
            .filter(c => c.status === 'APROVADO' && String(c.fornecedorId) === selectFornecedor.value)
            .map(c => `<option value="${Number(c.id)}">${escapeHtml(c.numeroContrato)} · ${escapeHtml(c.objeto)}</option>`).join('');
        selectContrato.value = anterior;
    }
    function alterarTipo() {
        const servico = tipoDocumento.value === 'SERVICO';
        document.getElementById('dadosServicoCompra').hidden = !servico;
        document.getElementById('itensMercadoriaCompra').hidden = servico;
        document.querySelectorAll('#itensMercadoriaCompra input, #itensMercadoriaCompra select, #itensMercadoriaCompra button').forEach(campo => { campo.disabled = servico; });
        valorServico.disabled = descricaoServico.disabled = !servico;
        valorServico.required = descricaoServico.required = selectContrato.required = notaFiscal.required = servico;
        if (servico) elementoTotal.textContent = moeda(valorServico.value);
        else atualizarTotal(tbody, elementoTotal);
        preencherContratos();
    }
    selectFornecedor.addEventListener('change', preencherContratos);
    tipoDocumento.addEventListener('change', alterarTipo);
    valorServico.addEventListener('input', () => { elementoTotal.textContent = moeda(valorServico.value); });
    produtosPorId = new Map(catalogo.map(p => [String(p.id), p]));
    selectFornecedor.innerHTML = '<option value="">Selecione um fornecedor</option>' + fornecedores
        .map(f => `<option value="${escapeHtml(f.id)}">${escapeHtml(f.nome)}</option>`).join('');
    const primeiraLinha = tbody.rows[0];
    primeiraLinha.querySelector('[name="produto"]').innerHTML = '<option value="">Selecione um produto</option>' + catalogo
        .map(p => `<option value="${escapeHtml(p.id)}">${escapeHtml(p.nome)} (${escapeHtml(p.codigo)})</option>`).join('');
    aplicarControleValidade(primeiraLinha, null);
    primeiraLinha.dataset.subtotal = '0';
    modeloLinha = primeiraLinha.cloneNode(true);
    alterarTipo();

    tbody.addEventListener('change', evento => {
        if (!evento.target.matches('[name="produto"]')) return;
        const linha = evento.target.closest('tr');
        const produto = produtosPorId.get(String(evento.target.value));
        const campoPreco = linha.querySelector('.preco-unitario');
        campoPreco.dataset.preco = String(Number(produto?.precoCompra) || 0);
        campoPreco.textContent = moeda(produto?.precoCompra);
        linha.querySelector('[name="lote"]').value = '';
        linha.querySelector('[name="validade"]').value = '';
        aplicarControleValidade(linha, produto);
        recalcularLinha(linha);
        atualizarTotal(tbody, elementoTotal);
    });
    tbody.addEventListener('input', evento => {
        if (!evento.target.matches('[name="quantidade"]')) return;
        recalcularLinha(evento.target.closest('tr'));
        atualizarTotal(tbody, elementoTotal);
    });
    tbody.addEventListener('keydown', evento => {
        if (evento.key !== 'Enter' || !evento.target.matches('[name="quantidade"]')) return;
        evento.preventDefault();
        const linha = evento.target.closest('tr');
        if (!linha.querySelector('[name="produto"]').value || !evento.target.value || !evento.target.checkValidity()) return;
        if (!linha.nextElementSibling) adicionarLinha(tbody);
        linha.nextElementSibling.querySelector('[name="produto"]').focus();
    });
    tbody.addEventListener('click', evento => {
        const botao = evento.target.closest('[data-remover-item]');
        if (botao) removerLinha(tbody, botao.closest('tr'), elementoTotal);
    });
    botaoAdicionar.addEventListener('click', () => adicionarLinha(tbody).querySelector('[name="produto"]').focus());

    let registrando = false;
    botaoFinalizar.addEventListener('click', async () => {
        if (registrando) return;
        limparMensagem(form);
        const fornecedorId = selectFornecedor.value;
        const data = inputData.value;
        const servico = tipoDocumento.value === 'SERVICO';
        const itens = servico ? [] : coletarItens(tbody);
        const erro = servico
            ? (!fornecedorId ? 'Selecione um fornecedor.' : !data ? 'Informe a data da compra.' : !selectContrato.value ? 'Selecione um contrato aprovado do fornecedor.' : !notaFiscal.value.trim() ? 'Informe a nota fiscal do serviço.' : !descricaoServico.value.trim() ? 'Descreva o serviço.' : !valorServico.value || !valorServico.checkValidity() || Number(valorServico.value) <= 0 ? 'Informe um valor válido para o serviço.' : null)
            : validarCompra(fornecedorId, data, itens);
        if (erro) { mostrarErro(form, erro); return; }
        registrando = true;
        iniciarCarregando(botaoFinalizar, 'Registrando...');
        try {
            const pedido = servico ? { fornecedorId: Number(fornecedorId), data, valorServico: Number(valorServico.value), descricaoServico: descricaoServico.value.trim() } : toCompraDTO({ fornecedorId, data, itens });
            const resultado = await criarCompra({ ...pedido, tipoDocumento: tipoDocumento.value, notaFiscal: notaFiscal.value.trim(), contratoId: selectContrato.value ? Number(selectContrato.value) : null });
            window.location.href = servico || resultado.status === 'PENDENTE_APROVACAO' ? 'compliance.html' : 'dashboard.html';
        } catch (falha) {
            mostrarErro(form, falha.message || 'Não foi possível registrar a compra.');
        } finally {
            registrando = false;
            pararCarregando(botaoFinalizar);
        }
    });
    form.addEventListener('submit', evento => { evento.preventDefault(); botaoFinalizar.click(); });
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;
    const form = document.getElementById('formCompra');
    if (!form) return;
    const botao = document.getElementById('btnFinalizarCompra');
    botao.disabled = true;
    inicializarTelaCompras(form)
        .then(() => { botao.disabled = false; })
        .catch(erro => mostrarErro(form, erro.message || 'Não foi possível carregar os produtos. Atualize a página.'));
});
