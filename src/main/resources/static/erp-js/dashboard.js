/** Preenche os indicadores gerais e a comparação mensal de faturamento. */
import { buscarIndicadores } from './dashboardService.js';
import { buscarFaturamentoMensal } from './faturamentoService.js';
import { estaAutenticado } from './authService.js';
import { temAcesso } from './planService.js';
import { buscarIndicadoresEstrategicos } from './estrategicoService.js';
import { esc, numero, barra } from './adminUi.js';

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

const ICONES_FALLBACK = {
    'users-add': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M19 8v6M16 11h6"/>',
    users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>',
    'package-add': '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="M3 8v9l9 5 9-5V8M12 13v9M18 3v6M15 6h6"/>',
    package: '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="M3 8v9l9 5 9-5V8M12 13v9"/>',
    layers: '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/>',
    'cart-down': '<circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.7 13.4a2 2 0 0 0 2 1.6h9.7a2 2 0 0 0 2-1.6L23 6H6"/><path d="M15 8v5m-2-2 2 2 2-2"/>',
    'cart-up': '<circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.7 13.4a2 2 0 0 0 2 1.6h9.7a2 2 0 0 0 2-1.6L23 6H6"/><path d="M15 13V8m-2 2 2-2 2 2"/>',
    scan: '<path d="M4 7V5a1 1 0 0 1 1-1h2M17 4h2a1 1 0 0 1 1 1v2M20 17v2a1 1 0 0 1-1 1h-2M7 20H5a1 1 0 0 1-1-1v-2M3 12h18"/><path d="M8 9v6m4-6v6m4-6v6"/>',
    boxes: '<path d="m12 2 8 4.5v9L12 20l-8-4.5v-9L12 2Z"/><path d="m4 6.5 8 4.5 8-4.5M12 11v9.0M2 17l7 4m13-4-7 4"/>',
    sparkles: '<path d="m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"/><path d="m19 14 1.2 2.8L23 18l-2.8 1.2L19 22l-1.2-2.8L15 18l2.8-1.2L19 14ZM5 2l.8 2.2L8 5l-2.2.8L5 8l-.8-2.2L2 5l2.2-.8L5 2Z"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>'
};

function aplicarFallbackImagem(img) {
    if (!img || img.dataset.fallbackAplicado === 'true') return;
    const area = img.closest('.acao-icone');
    if (!area) return;

    img.dataset.fallbackAplicado = 'true';
    img.hidden = true;
    area.classList.add('sem-imagem');

    const fallback = document.createElement('span');
    fallback.className = 'icone-imagem-fallback';
    fallback.setAttribute('aria-hidden', 'true');
    const paths = ICONES_FALLBACK[img.dataset.fallback] || ICONES_FALLBACK.grid;
    fallback.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" focusable="false">${paths}</svg>`;
    area.appendChild(fallback);
}

document.addEventListener('DOMContentLoaded', () => {
    // Trata falha de rede, arquivo inválido e imagem que terminou de carregar antes do listener.
    document.querySelectorAll('.acao img.icone-imagem').forEach((img) => {
        img.addEventListener('error', () => aplicarFallbackImagem(img), { once: true });
        if (img.complete && img.naturalWidth === 0) aplicarFallbackImagem(img);
    });
    if (!estaAutenticado()) return;
    carregarIndicadores();
    carregarEstrategico();

    const referencia = document.getElementById('mesReferencia');
    if (referencia) {
        referencia.value = mesAtual();
        referencia.addEventListener('change', carregarFaturamento);
    }
    carregarFaturamento();
});

/** Indicadores estratégicos: o backend só devolve as seções que o usuário pode ver; as demais ficam ocultas. */
async function carregarEstrategico() {
    const secao = document.getElementById('estrategico');
    if (!secao) return;
    try {
        const dados = await buscarIndicadoresEstrategicos();
        let algum = false;
        secao.querySelectorAll('#cartoesEstrategicos [data-ind]').forEach((cartao) => {
            const valor = dados[cartao.dataset.ind];
            if (valor === undefined) { cartao.hidden = true; return; }
            algum = true;
            cartao.querySelector('.indicador').textContent = numero(valor) + (cartao.dataset.sufixo || '');
        });
        if (!algum) return;
        secao.hidden = false;
        const por = dados.desempenhoPorSquad;
        if (por?.length) {
            document.getElementById('desempenhoSquads').innerHTML = `<h3>Desempenho por Squad</h3><table class="tabela"><thead><tr><th>Squad</th><th>Objetivos</th><th>Progresso</th></tr></thead><tbody>${por.map((s) => `<tr><td>${esc(s.nome)}</td><td>${esc(s.objetivos)}</td><td>${barra(s.progresso)}</td></tr>`).join('')}</tbody></table>`;
        }
    } catch (erro) {
        // Sem permissão (403) a seção simplesmente não aparece; outros erros são mostrados.
        if (erro.status === 403) return;
        secao.hidden = false;
        const m = document.getElementById('estrategicoMsg'); m.textContent = erro.message || 'Não foi possível carregar os indicadores estratégicos.'; m.dataset.tipo = 'erro'; m.hidden = false;
    }
}
