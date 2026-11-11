/** financeiro.html: contas a receber, cobranças Pix pela fintech (simulada), cartões de resumo e trilha de auditoria. */
import { apiService } from './apiService.js';
import { estaAutenticado } from './authService.js';
import { escapeHtml as e, iniciarCarregando, pararCarregando } from './uiService.js';
 
const $ = (seletor) => document.querySelector(seletor);
const moeda = (v) => Number(v || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
const dataBr = (v) => (v ? new Date(`${String(v).slice(0, 10)}T12:00:00`).toLocaleDateString('pt-BR') : '—');
const quando = (v) => (v ? new Date(v).toLocaleString('pt-BR') : '—');
const CLASSE_RESULTADO = { aceito: 'normal', criada: 'normal', duplicado: 'baixo', rejeitado: 'sem-estoque' };
const MODOS = [
    ['normal', 'Pagamento normal'],
    ['repetido', 'Reenviar o mesmo evento'],
    ['assinatura-invalida', 'Assinatura inválida'],
    ['valor-divergente', 'Valor divergente'],
    ['timestamp-antigo', 'Carimbo antigo (replay)'],
];
 
let carregando = false;
let repetir = false;
const controller = new AbortController();
 
function linhaConta(c) {
    const status = String(c.statusConta || '').toLowerCase();
    const paga = status === 'pago';
    const rotulo = paga ? 'Paga' : status === 'recebido' ? 'Recebida no caixa' : 'Pendente';
    let cobranca = '—';
    let acao = '—';
    if (!paga && c.codigo && c.statusCobranca === 'pendente') {
        cobranca = `${e(c.codigo)} <small class="status-detalhe">aguardando pagamento</small>`;
        acao = `<select data-modo="${e(c.codigo)}">${MODOS.map(([v, t]) => `<option value="${v}">${t}</option>`).join('')}</select> `
            + `<button class="acao-mini" data-simular="${e(c.codigo)}" type="button">Simular fintech</button>`;
    } else if (!paga) {
        acao = `<button class="acao-mini" data-gerar="${e(c.contaId)}" type="button">Gerar cobrança Pix</button>`;
    } else if (c.codigo) {
        cobranca = `${e(c.codigo)} <small class="status-detalhe">paga</small>`;
        // Cobrança já paga: permite reenviar o mesmo evento para provar a idempotência (resultado "duplicado").
        acao = `<button class="acao-mini" data-simular="${e(c.codigo)}" data-modo-fixo="repetido" type="button">Reenviar evento</button>`;
    }
    return `<tr><td>#${e(c.contaId)}</td><td>${e(c.descricao || '—')}</td><td>${moeda(c.valor)}</td>`
        + `<td>${dataBr(c.vencimento)}</td><td><span class="status ${paga ? 'normal' : 'baixo'}">${rotulo}</span></td>`
        + `<td>${cobranca}</td><td>${acao}</td></tr>`;
}
 
const linhaAuditoria = (a) => `<tr><td>${e(quando(a.recebidoEm))}</td><td>${e(a.codigo || '—')}</td>`
    + `<td>${e(a.eventoId || '—')}</td><td><span class="status ${CLASSE_RESULTADO[a.resultado] || 'baixo'}">${e(a.resultado)}</span></td>`
    + `<td>${e(a.motivo || '')}</td></tr>`;
 
function consultaAuditoria() {
    const parametros = new URLSearchParams();
    const resultado = $('#filtroResultado')?.value;
    const data = $('#filtroData')?.value;
    if (resultado) parametros.set('resultado', resultado);
    if (data) parametros.set('data', data);
    const texto = parametros.toString();
    return `/financeiro/auditoria${texto ? `?${texto}` : ''}`;
}
 
function desenharResumo(r) {
    $('#cartaoPendente').textContent = moeda(r.pendente);
    $('#cartaoQtdPendentes').textContent = `${r.qtdPendentes} conta(s) ainda não confirmadas pela fintech`;
    $('#cartaoLiquidado').textContent = moeda(r.liquidadoHoje);
    $('#cartaoAguardando').textContent = String(r.aguardando);
    $('#cartaoRejeitados').textContent = String(r.rejeitadosHoje);
}
 
async function carregar() {
    if (carregando) { repetir = true; return; }
    carregando = true;
    try {
        const [contas, auditoria, resumo] = await Promise.all([
            apiService.get('/financeiro/cobrancas'),
            apiService.get(consultaAuditoria()),
            apiService.get('/financeiro/resumo'),
        ]);
        desenharResumo(resumo);
        $('#listaContas').innerHTML = contas.length
            ? contas.map(linhaConta).join('')
            : '<tr><td colspan="7" class="estado-vazio">Nenhuma conta a receber encontrada.</td></tr>';
        const filtrando = Boolean($('#filtroResultado')?.value || $('#filtroData')?.value);
        $('#listaAuditoria').innerHTML = auditoria.length
            ? auditoria.map(linhaAuditoria).join('')
            : `<tr><td colspan="5" class="estado-vazio">${filtrando ? 'Nenhum evento para esse filtro.' : 'Nenhum evento ainda.'}</td></tr>`;
        $('#estadoFinanceiro').textContent = `Atualizado às ${new Date().toLocaleTimeString('pt-BR')}`;
    } catch (erro) {
        $('#estadoFinanceiro').textContent = erro.message;
    } finally {
        carregando = false;
        if (repetir) { repetir = false; void carregar(); }
    }
}
 
async function aoClicar(evento) {
    const gerar = evento.target.closest('[data-gerar]');
    const simular = evento.target.closest('[data-simular]');
    const botao = gerar || simular;
    if (!botao) return;
    const aviso = $('#resultadoSimulacao');
    iniciarCarregando(botao, 'Enviando...');
    try {
        if (gerar) {
            await apiService.post('/financeiro/cobrancas', { contaReceberId: Number(gerar.dataset.gerar) });
            aviso.textContent = 'Cobrança Pix criada na fintech.';
        } else {
            const codigo = simular.dataset.simular;
            const modo = simular.dataset.modoFixo || document.querySelector(`select[data-modo="${CSS.escape(codigo)}"]`).value;
            const r = await apiService.post(
                `/fintech-sim/cobrancas/${encodeURIComponent(codigo)}/simular?modo=${encodeURIComponent(modo)}`, {});
            aviso.textContent = `Modo "${r.modo}": o webhook respondeu HTTP ${r.statusHttp} — ${r.resposta}`;
        }
        await carregar();
    } catch (erro) {
        aviso.textContent = erro.message;
    } finally {
        pararCarregando(botao);
    }
}
 
async function acompanhar() {
    while (!controller.signal.aborted) {
        try {
            await apiService.events('/pos/eventos', {
                signal: controller.signal,
                onEvent: (tipo) => { if (tipo === 'financeiro-atualizado' || tipo === 'conectado') void carregar(); },
            });
        } catch (erro) {
            if (controller.signal.aborted) return;
            if (erro.status === 401) { window.location.href = 'login.html'; return; }
        }
        if (!controller.signal.aborted) await new Promise((resolve) => setTimeout(resolve, 1500));
    }
}
 
document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;
    $('#listaContas').addEventListener('click', aoClicar);
    $('#filtroResultado')?.addEventListener('change', () => void carregar());
    $('#filtroData')?.addEventListener('change', () => void carregar());
    $('#limparFiltros')?.addEventListener('click', () => {
        $('#filtroResultado').value = '';
        $('#filtroData').value = '';
        void carregar();
    });
    window.addEventListener('pagehide', () => controller.abort(), { once: true });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) void carregar(); });
    void carregar();
    void acompanhar();
});
 