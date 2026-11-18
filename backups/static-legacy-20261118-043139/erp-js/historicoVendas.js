import { estaAutenticado } from './authService.js';
import { listarVendas, buscarVenda } from './vendasService.js';
import { escapeHtml } from './uiService.js';

const $ = (seletor) => document.querySelector(seletor);
const moeda = (valor) => Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
let consultaAtual = 0;
let detalheAtual = 0;

function dataLocal(valor, origem) {
    const data = new Date(valor);
    if (Number.isNaN(data.getTime())) return 'Data indisponível';
    return origem === 'POS'
        ? data.toLocaleString('pt-BR')
        : data.toLocaleDateString('pt-BR');
}

function origemDaVenda(venda) {
    return venda.origem === 'POS' ? 'POS' : 'ERP';
}

function mostrarVendas(vendas, filtrado) {
    $('#resumoHistorico').textContent = `${vendas.length} ${vendas.length === 1 ? 'venda encontrada' : 'vendas encontradas'}`;
    $('#listaHistorico').innerHTML = vendas.length
        ? vendas.map((venda) => `<tr>
            <td>#${escapeHtml(venda.id)}</td>
            <td>${escapeHtml(dataLocal(venda.data, venda.origem))}</td>
            <td>${origemDaVenda(venda)}</td>
            <td>${escapeHtml(venda.cliente)}</td>
            <td>${moeda(venda.total)}</td>
            <td><button class="botao secundario" type="button" data-detalhe-venda="${escapeHtml(venda.id)}">Comprovante</button></td>
        </tr>`).join('')
        : `<tr><td colspan="6" class="estado-vazio">${filtrado ? 'Nenhuma venda encontrada para essas datas.' : 'Nenhuma venda registrada.'}</td></tr>`;
}

async function carregarVendas() {
    const consulta = ++consultaAtual;
    const data = $('#dataHistorico').value;
    const inicio = $('#inicioHistorico').value;
    const fim = $('#fimHistorico').value;
    if (inicio && fim && inicio > fim) {
        $('#resumoHistorico').textContent = 'A data inicial deve ser anterior ou igual à data final.';
        $('#listaHistorico').innerHTML = '<tr><td colspan="6" class="estado-vazio">Corrija o intervalo e pesquise novamente.</td></tr>';
        $('#buscarHistorico').disabled = false;
        return;
    }

    const botao = $('#buscarHistorico');
    botao.disabled = true;
    $('#resumoHistorico').textContent = 'Carregando vendas...';
    $('#listaHistorico').innerHTML = '<tr><td colspan="6" class="estado-vazio">Carregando vendas...</td></tr>';
    try {
        const vendas = await listarVendas({ data, inicio, fim });
        if (consulta === consultaAtual) mostrarVendas(vendas, Boolean(data || inicio || fim));
    } catch (erro) {
        if (consulta === consultaAtual) {
            $('#resumoHistorico').textContent = 'Não foi possível consultar as vendas.';
            $('#listaHistorico').innerHTML = `<tr><td colspan="6" class="estado-vazio">${escapeHtml(erro.message || 'Tente novamente.')}</td></tr>`;
        }
    } finally {
        if (consulta === consultaAtual) botao.disabled = false;
    }
}

function mostrarDetalhes(venda) {
    const itens = venda.itens || [];
    const pagamento = venda.origem === 'POS' && venda.formaPagamento
        ? `<p>Pagamento: ${escapeHtml({ DINHEIRO: 'Dinheiro', PIX: 'Pix', CREDITO: 'Cartão de crédito', DEBITO: 'Cartão de débito' }[venda.formaPagamento] || venda.formaPagamento)}</p>`
        : '';
    $('#tituloDetalhesVenda').textContent = `Comprovante da venda #${venda.id}`;
    $('#conteudoDetalhesVenda').innerHTML = `
        <h3>${escapeHtml(venda.empresa || 'Empresa')}</h3><p class="ajuda-campo">Comprovante não fiscal — não substitui documento fiscal.</p>
        <div class="historico-meta">
            <div><strong>Data</strong><br>${escapeHtml(dataLocal(venda.data, venda.origem))}</div>
            <div><strong>Origem</strong><br>${origemDaVenda(venda)}</div>
            <div><strong>Cliente</strong><br>${escapeHtml(venda.cliente)}</div>
            <div><strong>Operador</strong><br>${escapeHtml(venda.operador || 'Não informado')}</div>
        </div>
        <div class="historico-tabela-scroll"><table class="tabela">
            <thead><tr><th>Produto</th><th>Qtd.</th><th>Preço unitário</th><th>Subtotal</th></tr></thead>
            <tbody>${itens.length ? itens.map((item) => `<tr><td>${escapeHtml(item.nome)}${item.codigo ? ` <small>(${escapeHtml(item.codigo)})</small>` : ''}</td><td>${escapeHtml(item.quantidade)}</td><td>${moeda(item.precoUnitario)}</td><td>${moeda(item.subtotal)}</td></tr>`).join('') : '<tr><td colspan="4" class="estado-vazio">Itens não disponíveis para esta venda.</td></tr>'}</tbody>
        </table></div>
        ${venda.origem === 'POS' && Number(venda.desconto) > 0 ? `<p class="historico-total">Desconto: ${moeda(venda.desconto)}</p>` : ''}
        <p class="historico-total"><strong>Total: ${moeda(venda.total)}</strong></p>
        ${pagamento}
        ${venda.origem === 'POS' ? `<p>Valor recebido: ${moeda(venda.valorRecebido)} · Troco: ${moeda(venda.troco)}</p>` : '<p class="ajuda-campo">Registro da venda no ERP. Este documento não comprova quitação do pagamento.</p>'}
        ${(venda.lotes || []).length ? `<p class="ajuda-campo">Lotes utilizados: ${venda.lotes.map(l => `${escapeHtml(l.nome)} — ${escapeHtml(l.codigo)} (${escapeHtml(l.quantidade)} un.)`).join('; ')}</p>` : ''}`;
    $('#imprimirComprovante').disabled = false;
}

function configurarHistorico() {
    const formulario = $('#filtrosHistorico');
    formulario.addEventListener('submit', (evento) => { evento.preventDefault(); void carregarVendas(); });
    $('#limparHistorico').addEventListener('click', () => { formulario.reset(); void carregarVendas(); });
    $('#dataHistorico').addEventListener('input', () => {
        if ($('#dataHistorico').value) { $('#inicioHistorico').value = ''; $('#fimHistorico').value = ''; }
    });
    for (const seletor of ['#inicioHistorico', '#fimHistorico']) {
        $(seletor).addEventListener('input', () => { if ($(seletor).value) $('#dataHistorico').value = ''; });
    }
    $('#listaHistorico').addEventListener('click', async (evento) => {
        const botao = evento.target.closest('[data-detalhe-venda]');
        if (!botao || botao.disabled) return;
        const detalhe = ++detalheAtual;
        botao.disabled = true;
        $('#tituloDetalhesVenda').textContent = 'Comprovante da venda';
        $('#imprimirComprovante').disabled = true;
        $('#conteudoDetalhesVenda').textContent = 'Carregando detalhes...';
        $('#detalhesVenda').showModal();
        try {
            const venda = await buscarVenda(botao.dataset.detalheVenda);
            if (detalhe === detalheAtual && $('#detalhesVenda').open) mostrarDetalhes(venda);
        } catch (erro) {
            if (detalhe === detalheAtual && $('#detalhesVenda').open) $('#conteudoDetalhesVenda').textContent = erro.message || 'Não foi possível abrir a venda.';
        } finally {
            botao.disabled = false;
        }
    });
    $('#imprimirComprovante').addEventListener('click', () => { document.body.dataset.impressao='comprovante'; window.print(); });
    window.addEventListener('afterprint', () => delete document.body.dataset.impressao);
    $('#fecharDetalhesVenda').addEventListener('click', () => $('#detalhesVenda').close());
    $('#detalhesVenda').addEventListener('close', () => { detalheAtual++; });
    void carregarVendas();
}

document.addEventListener('DOMContentLoaded', () => {
    if (estaAutenticado()) configurarHistorico();
});
