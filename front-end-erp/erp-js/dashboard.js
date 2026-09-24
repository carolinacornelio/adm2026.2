/**
 * dashboard.js
 * ---------------------------------------------------------------------------
 * Dashboard básico + indicadores de faturamento do Plano Intermediário.
 * ---------------------------------------------------------------------------
 */

import { buscarIndicadores } from './dashboardService.js';
import { buscarFaturamentoMensal } from '../../.github/modernize/java-upgrade/hooks/scripts/atu/atualização-erp/faturamentoService.js';
import { estaAutenticado } from './authService.js';
import { temAcesso } from './planService.js';

function formatarMoeda(valor) {
    return Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function formatarPercentual(valor) {
    const sinal = Number(valor) > 0 ? '+' : '';
    return `${sinal}${Number(valor || 0).toFixed(2).replace('.', ',')}%`;
}

async function carregarIndicadores() {
    const cartoes = document.querySelectorAll('.cartoes .cartao .indicador');
    if (cartoes.length === 0) return;

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

async function carregarFaturamento() {
    if (!temAcesso('PLUS')) return;

    const referencia = document.getElementById('mesReferencia');
    const mes = referencia?.value || new Date().toISOString().slice(0, 7);

    try {
        const dados = await buscarFaturamentoMensal(mes);

        document.getElementById('faturamentoAtual').textContent = formatarMoeda(dados.faturamentoAtual);
        document.getElementById('faturamentoAnterior').textContent = formatarMoeda(dados.faturamentoAnterior);
        document.getElementById('mesAtualLabel').textContent = dados.mesAtualLabel;
        document.getElementById('mesAnteriorLabel').textContent = dados.mesAnteriorLabel;
        document.getElementById('variacaoFaturamento').textContent = formatarPercentual(dados.variacaoPercentual);

        const texto = dados.variacaoPercentual >= 0
            ? 'O faturamento do período selecionado está acima do mês anterior.'
            : 'O faturamento do período selecionado está abaixo do mês anterior.';

        document.getElementById('analiseFaturamento').textContent = texto;
    } catch (erro) {
        console.error('Não foi possível carregar o faturamento:', erro);
        document.getElementById('analiseFaturamento').textContent =
            'Não foi possível carregar os dados de faturamento.';
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    carregarIndicadores();

    const referencia = document.getElementById('mesReferencia');
    if (referencia) {
        referencia.value = new Date().toISOString().slice(0, 7);
        referencia.addEventListener('change', carregarFaturamento);
    }

    carregarFaturamento();
});
