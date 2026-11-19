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

let consultaFaturamento = 0;
async function carregarFaturamento() {
    if (!temAcesso('PLUS')) return;

    const referencia = document.getElementById('mesReferencia');
    const analise = document.getElementById('analiseFaturamento');
    if (!referencia || !analise) return;

    const consulta = ++consultaFaturamento;
    const painel = analise.closest('.dashboard-economico');
    painel?.setAttribute('aria-busy', 'true');
    try {
        const dados = await buscarFaturamentoMensal(referencia.value || mesAtual());
        if (consulta !== consultaFaturamento) return;
        document.getElementById('faturamentoAtual').textContent = formatarMoeda(dados.faturamentoAtual);
        document.getElementById('faturamentoAnterior').textContent = formatarMoeda(dados.faturamentoAnterior);
        document.getElementById('mesAtualLabel').textContent = dados.mesAtualLabel;
        document.getElementById('mesAnteriorLabel').textContent = dados.mesAnteriorLabel;
        document.getElementById('variacaoFaturamento').textContent = formatarPercentual(dados.variacaoPercentual);
        analise.textContent = textoDaAnalise(dados);
    } catch (erro) {
        if (consulta !== consultaFaturamento) return;
        console.error('Não foi possível carregar o faturamento:', erro);
        analise.textContent = erro.message || 'Não foi possível carregar os dados de faturamento.';
    } finally {
        if (consulta === consultaFaturamento) painel?.removeAttribute('aria-busy');
    }
}

document.addEventListener('DOMContentLoaded', () => {
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
