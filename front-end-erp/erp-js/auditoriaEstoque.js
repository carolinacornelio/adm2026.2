/**
 * auditoriaEstoque.js
 * ---------------------------------------------------------------------------
 * Desafio 5 - Controle de Qualidade.
 *
 * Quadro "Auditoria de estoque" da tela estoque.html: compara o estoque gravado
 * no sistema com o saldo calculado pelo histórico de transações
 * (entradas - saídas ± ajustes do inventário físico) e lista as transações do
 * dia, com o saldo conferido pela segunda verificação do hotfix.
 *
 * Fica em um módulo próprio para não alterar a lógica de estoque.js.
 * ---------------------------------------------------------------------------
 */
import { apiService } from './apiService.js';
import { estaAutenticado } from './authService.js';
import { escapeHtml as e } from './uiService.js';
import { aoMovimentar } from './estoqueSync.js';

const $ = (seletor) => document.querySelector(seletor);

function linhaAuditoria(item) {
    const divergente = item.situacao !== 'OK';
    const diferenca = Number(item.diferenca);
    return `<tr>
        <td>${e(item.nome)}</td>
        <td>${e(item.entradas)}</td>
        <td>${e(item.saidas)}</td>
        <td>${e(item.ajustes)}</td>
        <td>${e(item.estoqueHistorico)}</td>
        <td>${e(item.estoqueSistema)}</td>
        <td>${diferenca > 0 ? '+' : ''}${e(diferenca)}</td>
        <td><span class="status ${divergente ? 'sem-estoque' : 'normal'}">${divergente ? 'Divergente' : 'OK'}</span></td>
    </tr>`;
}

function linhaMovimentacao(m) {
    return `<tr>
        <td>${e(m.dataHora)}</td>
        <td>${e(m.produto)}</td>
        <td>${e(m.tipo)}</td>
        <td>${e(m.quantidade)}</td>
        <td>${e(m.motivo || '')}</td>
        <td>${m.saldoConferido == null ? '—' : e(m.saldoConferido)}</td>
    </tr>`;
}

async function carregarAuditoria() {
    const tbody = $('#listaAuditoria');
    const resumo = $('#resumoAuditoria');
    if (!tbody) return;
    try {
        const itens = await apiService.get('/estoque/auditoria');
        tbody.innerHTML = itens.map(linhaAuditoria).join('')
            || '<tr><td colspan="8" class="estado-vazio">Nenhum produto cadastrado ainda.</td></tr>';
        const divergentes = itens.filter((i) => i.situacao !== 'OK').length;
        if (resumo) {
            resumo.innerHTML = divergentes === 0
                ? '<span class="status normal">Nenhuma divergência encontrada</span>'
                : `<span class="status sem-estoque">${divergentes} produto(s) com divergência</span>`;
        }
    } catch (erro) {
        console.error(erro);
        tbody.innerHTML = '<tr><td colspan="8" class="estado-vazio">Não foi possível carregar a auditoria.</td></tr>';
    }
}

async function carregarMovimentacoes() {
    const tbody = $('#listaMovimentacoes');
    if (!tbody) return;
    const data = $('#dataMovimentacoes')?.value || '';
    try {
        const query = data ? `?data=${encodeURIComponent(data)}` : '';
        const itens = await apiService.get(`/estoque/movimentacoes${query}`);
        tbody.innerHTML = itens.map(linhaMovimentacao).join('')
            || '<tr><td colspan="6" class="estado-vazio">Nenhuma transação de estoque nesse dia.</td></tr>';
    } catch (erro) {
        console.error(erro);
        tbody.innerHTML = '<tr><td colspan="6" class="estado-vazio">Não foi possível carregar as transações.</td></tr>';
    }
}

function atualizar() {
    void carregarAuditoria();
    void carregarMovimentacoes();
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado() || !$('#auditoriaEstoquePainel')) return;

    const campoData = $('#dataMovimentacoes');
    if (campoData) {
        const hoje = new Date();
        campoData.value = new Date(hoje.getTime() - hoje.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
        campoData.addEventListener('change', () => void carregarMovimentacoes());
    }
    $('#atualizarAuditoria')?.addEventListener('click', atualizar);

    // Recarrega junto com o restante da tela: compra/venda em outra aba e volta para a aba.
    aoMovimentar(atualizar);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) atualizar(); });
    atualizar();
});
