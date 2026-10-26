/** Preenche os indicadores gerais e a comparação mensal de faturamento. */
import { buscarIndicadores } from './dashboardService.js';
import { buscarFaturamentoMensal } from './faturamentoService.js';
import { estaAutenticado, getUsuarioLogado } from './authService.js';
import { temAcesso } from './planService.js';

function formatarMoeda(valor) {
    return Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function formatarPercentual(valor) {
    const numero = Number(valor || 0);
    const sinal = numero > 0 ? '+' : '';
    return `${sinal}${numero.toFixed(2).replace('.', ',')}%`;
}

function mesAtual() {
    const data = new Date();
    return `${data.getFullYear()}-${String(data.getMonth() + 1).padStart(2, '0')}`;
}

function organizarAcessoRapido() {
    const area = document.querySelector('.acoes');
    if (!area) return;
    const usuario = getUsuarioLogado();
    const chave = `erp-acesso-rapido:${usuario?.id || usuario?.email || 'usuario'}`;
    let recentes = [];
    try {
        recentes = JSON.parse(localStorage.getItem(chave) || '[]');
        if (!Array.isArray(recentes)) recentes = [];
    } catch (_) { recentes = []; }

    const cards = [...area.querySelectorAll(':scope > .acao')];
    const nome = card => card.querySelector('strong')?.textContent.trim().toLocaleLowerCase('pt-BR') || '';
    const ordemAlfabetica = (a, b) => nome(a).localeCompare(nome(b), 'pt-BR');
    cards.sort((a, b) => {
        const posA = recentes.indexOf(a.getAttribute('href'));
        const posB = recentes.indexOf(b.getAttribute('href'));
        if (posA !== -1 || posB !== -1) {
            if (posA === -1) return 1;
            if (posB === -1) return -1;
            if (posA !== posB) return posA - posB;
        }
        return ordemAlfabetica(a, b);
    }).forEach(card => area.appendChild(card));

    area.addEventListener('click', event => {
        const card = event.target.closest('.acao');
        if (!card || card.parentElement !== area) return;
        const href = card.getAttribute('href');
        recentes = [href, ...recentes.filter(item => item !== href)].slice(0, cards.length);
        localStorage.setItem(chave, JSON.stringify(recentes));
    });
}

async function carregarIndicadores() {
    const cartoes = document.querySelectorAll('#indicadoresGerais .indicador');
    if (!cartoes.length) return;

    try {
        const indicadores = await buscarIndicadores();
        const valores = [
            indicadores.totalPessoas,
            indicadores.totalProdutos,
            indicadores.totalCompras,
            indicadores.totalVendas,
        ];
        cartoes.forEach((elemento, indice) => {
            elemento.textContent = valores[indice] ?? 0;
        });
    } catch (erro) {
        console.error('Não foi possível carregar os indicadores do dashboard:', erro);
    }
}

function textoDaAnalise(dados) {
    const atual = Number(dados.faturamentoAtual || 0);
    const anterior = Number(dados.faturamentoAnterior || 0);
    const variacao = Number(dados.variacaoPercentual || 0);

    if (anterior === 0 && atual === 0) return 'Não houve faturamento registrado no mês selecionado nem no mês anterior.';
    if (anterior === 0) return 'Há faturamento no período selecionado, mas não há base no mês anterior para uma comparação percentual completa.';
    if (variacao > 0) return 'O faturamento do período selecionado está acima do mês anterior.';
    if (variacao < 0) return 'O faturamento do período selecionado está abaixo do mês anterior.';
    return 'O faturamento do período selecionado ficou igual ao mês anterior.';
}

async function carregarFaturamento() {
    if (!temAcesso('PLUS')) return;

    const referencia = document.getElementById('mesReferencia');
    const analise = document.getElementById('analiseFaturamento');
    if (!referencia || !analise) return;

    try {
        const dados = await buscarFaturamentoMensal(referencia.value || mesAtual());
        document.getElementById('faturamentoAtual').textContent = formatarMoeda(dados.faturamentoAtual);
        document.getElementById('faturamentoAnterior').textContent = formatarMoeda(dados.faturamentoAnterior);
        document.getElementById('mesAtualLabel').textContent = dados.mesAtualLabel;
        document.getElementById('mesAnteriorLabel').textContent = dados.mesAnteriorLabel;
        document.getElementById('variacaoFaturamento').textContent = formatarPercentual(dados.variacaoPercentual);
        analise.textContent = textoDaAnalise(dados);
    } catch (erro) {
        console.error('Não foi possível carregar o faturamento:', erro);
        analise.textContent = erro.message || 'Não foi possível carregar os dados de faturamento.';
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;
    organizarAcessoRapido();
    carregarIndicadores();

    const referencia = document.getElementById('mesReferencia');
    if (referencia) {
        referencia.value = mesAtual();
        referencia.addEventListener('change', carregarFaturamento);
    }
    carregarFaturamento();
});
