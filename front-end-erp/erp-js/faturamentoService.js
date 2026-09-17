/**
 * faturamentoService.js
 * ---------------------------------------------------------------------------
 * Comparação mensal de faturamento.
 *
 * ENDPOINT SPRING:
 * GET /api/dashboard/faturamento?mesReferencia=YYYY-MM
 *
 * Resposta esperada:
 * {
 *   mesAtual: "2026-09",
 *   faturamentoAtual: 38700.00,
 *   mesAnterior: "2026-08",
 *   faturamentoAnterior: 32500.00,
 *   variacaoPercentual: 19.08
 * }
 *
 * A conta deve ser feita no backend a partir das vendas da empresa logada.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js';
import { storageService } from './storageService.js';

function chaveMes(data) {
    return String(data || '').slice(0, 7);
}

function formatarMes(mes) {
    const [ano, numero] = mes.split('-').map(Number);
    return new Date(ano, numero - 1, 1).toLocaleDateString('pt-BR', {
        month: 'long',
        year: 'numeric',
    });
}

function mesAnterior(mes) {
    const [ano, numero] = mes.split('-').map(Number);
    const data = new Date(ano, numero - 2, 1);
    return `${data.getFullYear()}-${String(data.getMonth() + 1).padStart(2, '0')}`;
}

export async function buscarFaturamentoMensal(mesReferencia) {
    // ===== ETAPA SPRING BOOT =====
    // return apiService.get(`/dashboard/faturamento?mesReferencia=${encodeURIComponent(mesReferencia)}`);

    // ===== ETAPA ATUAL =====
    const vendas = storageService.listarPorUsuario('vendas');
    const atual = mesReferencia || new Date().toISOString().slice(0, 7);
    const anterior = mesAnterior(atual);

    const totalMes = (mes) => vendas
        .filter((venda) => chaveMes(venda.data) === mes)
        .reduce((soma, venda) => soma + Number(venda.total || 0), 0);

    const faturamentoAtual = totalMes(atual);
    const faturamentoAnterior = totalMes(anterior);

    const variacaoPercentual = faturamentoAnterior === 0
        ? (faturamentoAtual > 0 ? 100 : 0)
        : ((faturamentoAtual - faturamentoAnterior) / faturamentoAnterior) * 100;

    return {
        mesAtual: atual,
        mesAtualLabel: formatarMes(atual),
        faturamentoAtual,
        mesAnterior: anterior,
        mesAnteriorLabel: formatarMes(anterior),
        faturamentoAnterior,
        variacaoPercentual,
    };
}
