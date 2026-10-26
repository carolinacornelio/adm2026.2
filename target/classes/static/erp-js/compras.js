/**
 * compras.js
 * ---------------------------------------------------------------------------
 * Controller de compras.html. Permite escolher um fornecedor, adicionar
 * várias linhas de produto/quantidade, usa o preço cadastrado automaticamente
 * e calcula o total e, ao finalizar, registrar a compra (o que também dá
 * entrada no estoque — ver comprasService.js).
 *
 * Melhorias desta versão (Desafio "Feedback do Cliente"):
 *   1. Lote e validade só aparecem para produtos com controle de validade.
 *   2. Nova linha = clone de uma linha-modelo limpa (antes: clonava a última
 *      linha e ainda remontava todas as <option> com innerHTML).
 *   3. Formatador de moeda criado uma vez (antes: toLocaleString a cada uso).
 *   4. Digitar a quantidade recalcula só a linha alterada; o total geral é
 *      uma soma de números (antes: reformatava TODAS as linhas a cada tecla).
 *   5. Enter na quantidade abre a próxima linha e leva o foco ao produto.
 *   6. Busca de produto por Map (O(1)) em vez de find() em array.
 *   7. Data padrão em horário LOCAL (toISOString usa UTC e, no Brasil, após
 *      as 21h devolvia o dia seguinte) e rejeição de lote vencido no front.
 *   8. Após registrar, avisa as outras abas (estoque.html) para atualizarem.
 *
 * Compliance (Lei Anticorrupção):
 *   - Tipo de operação (mercadoria/serviço), contrato homologado e nota fiscal.
 *   - Compras >= R$ 10.000,00 ficam aguardando aprovação de alçada; o estoque
 *     só entra quando a aprovação final é registrada.
 *   - Histórico de pedidos com aprovação de alçada e pagamento (Regra de Ouro SoD).
 * ---------------------------------------------------------------------------
 */

import { listarPessoas } from './pessoasService.js';
import { listarProdutos } from './produtosService.js';
import { criarCompra, listarCompras } from './comprasService.js';
import { complianceService } from './complianceService.js';
import { estaAutenticado } from './authService.js';
import { fromPessoaDTO, fromProdutoDTO, toCompraDTO } from './dto.js';
import { iniciarCarregando, pararCarregando, mostrarErro, limparMensagem, escapeHtml, notificarSucesso, notificarErro } from './uiService.js';
import { avisarMovimentacao } from './estoqueSync.js';

let produtosPorId = new Map(); // id (texto) -> produto; carregado uma vez ao abrir a tela
let modeloLinha = null;        // linha "limpa" usada como molde para novas linhas

const formatadorMoeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

function formatarMoeda(valor) {
    return formatadorMoeda.format(Number(valor) || 0);
}

/** Data de hoje no fuso do usuário (YYYY-MM-DD). */
function hojeLocal() {
    const agora = new Date();
    const mes = String(agora.getMonth() + 1).padStart(2, '0');
    const dia = String(agora.getDate()).padStart(2, '0');
    return `${agora.getFullYear()}-${mes}-${dia}`;
}

/** Preenche um <select> com uma lista de pessoas (fornecedores). */
function popularSelectFornecedores(selectElement, fornecedores) {
    const opcoes = fornecedores
        .map((f) => `<option value="${escapeHtml(f.id)}">${escapeHtml(f.nome)}</option>`)
        .join('');
    selectElement.insertAdjacentHTML('beforeend', opcoes);
}

/** Preenche UM <select name="produto"> — usado só na linha-modelo, uma única vez. */
function popularSelectProdutos(selectElement, produtos) {
    const opcoes = produtos
        .map((p) => `<option value="${escapeHtml(p.id)}">${escapeHtml(p.nome)} (${escapeHtml(p.codigo)})</option>`)
        .join('');
    selectElement.innerHTML = `<option value="">Selecione um produto</option>${opcoes}`;
}

/** Lista somente contratos homologados (status APROVADO) para vincular à compra. */
async function popularSelectContratos(selectElement) {
    if (!selectElement) return;
    try {
        const contratos = await complianceService.listarContratos();
        const opcoes = contratos
            .filter((c) => c.status === 'APROVADO')
            .map((c) => `<option value="${escapeHtml(c.id)}">${escapeHtml(c.numeroContrato)} - ${escapeHtml(c.fornecedorNome)} (${formatarMoeda(c.valorTotal)})</option>`)
            .join('');
        selectElement.innerHTML = `<option value="">Selecione um contrato homologado</option>${opcoes}`;
    } catch (erro) {
        console.error('Erro ao listar contratos homologados:', erro);
    }
}

/** Mostra lote/validade só quando o produto exige; senão esconde e limpa. */
function aplicarControleValidade(linha, produto) {
    const exige = Boolean(produto?.controlaValidade);
    ['lote', 'validade'].forEach((nome) => {
        const campo = linha.querySelector(`[name="${nome}"]`);
        campo.style.display = exige ? '' : 'none';
        if (!exige) campo.value = '';
    });
}

/** Recalcula o subtotal de UMA linha (quantidade × preço) e guarda o valor numérico. */
function recalcularSubtotalLinha(linha) {
    const quantidade = Number(linha.querySelector('[name="quantidade"]').value) || 0;
    const preco = Number(linha.querySelector('.preco-unitario').dataset.preco) || 0;
    const subtotal = quantidade * preco;
    linha.querySelector('.coluna-subtotal').textContent = formatarMoeda(subtotal);
    linha.dataset.subtotal = String(subtotal);
    return subtotal;
}

/** Soma os subtotais já calculados (só números, sem tocar nas outras linhas). */
function atualizarTotalGeral(tbody, elementoTotal) {
    let total = 0;
    for (const linha of tbody.rows) total += Number(linha.dataset.subtotal) || 0;
    elementoTotal.textContent = formatarMoeda(total);
}

/** Cria uma nova linha a partir da linha-modelo limpa e a devolve. */
function adicionarLinha(tbody) {
    const novaLinha = modeloLinha.cloneNode(true);
    tbody.appendChild(novaLinha);
    return novaLinha;
}

/** Remove uma linha; se for a única restante, apenas limpa os campos dela. */
function removerLinha(tbody, linha, elementoTotal) {
    if (tbody.rows.length === 1) {
        linha.querySelector('[name="produto"]').value = '';
        linha.querySelector('[name="quantidade"]').value = '';
        linha.querySelector('.preco-unitario').dataset.preco = '0';
        linha.querySelector('.preco-unitario').textContent = formatarMoeda(0);
        aplicarControleValidade(linha, null);
        recalcularSubtotalLinha(linha);
    } else {
        linha.remove();
    }
    atualizarTotalGeral(tbody, elementoTotal);
}

/** Volta a tabela de itens para uma única linha limpa. */
function limparItens(tbody, elementoTotal) {
    tbody.replaceChildren(modeloLinha.cloneNode(true));
    atualizarTotalGeral(tbody, elementoTotal);
}

/** Lê todas as linhas preenchidas da tabela e monta a lista de itens para o DTO. */
function coletarItens(tbody) {
    const itens = [];
    for (const linha of tbody.rows) {
        const produtoId = linha.querySelector('[name="produto"]').value;
        const quantidade = Number(linha.querySelector('[name="quantidade"]').value);
        const precoUnitario = Number(linha.querySelector('.preco-unitario').dataset.preco);
        if (produtoId && quantidade > 0) {
            itens.push({
                produtoId,
                quantidade,
                precoUnitario,
                lote: linha.querySelector('[name="lote"]').value.trim(),
                validade: linha.querySelector('[name="validade"]').value,
            });
        }
    }
    return itens;
}

function validarCompra(fornecedorId, data, itens) {
    if (!fornecedorId) return 'Selecione um fornecedor.';
    if (!data) return 'Informe a data da compra.';
    if (itens.length === 0) return 'Adicione ao menos um produto com quantidade válida.';
    const hoje = hojeLocal();
    for (const item of itens) {
        const produto = produtosPorId.get(String(item.produtoId));
        if (produto?.controlaValidade) {
            if (!item.lote || !item.validade) return `Informe lote e validade para ${produto.nome}.`;
            // A validade de hoje é aceita; lotes vencidos não podem entrar (datas ISO comparam como texto).
            if (item.validade < hoje) return `O lote ${item.lote} de ${produto.nome} está vencido e não pode entrar.`;
        }
        if (!Number.isInteger(item.quantidade)) return 'Informe quantidades inteiras.';
    }
    return null;
}

/** Rótulo e cor da situação de alçada de cada pedido. */
function situacaoCompra(status) {
    switch (status) {
        case 'PENDENTE_APROVACAO': return { classe: 'badge-alta', rotulo: 'Aguardando aprovação' };
        case 'AGUARDANDO_NIVEL_2': return { classe: 'badge-critica', rotulo: 'Aguardando 2º nível' };
        case 'CONCLUIDA': return { classe: 'badge-conforme', rotulo: 'Aprovada' };
        case 'PAGO': return { classe: 'badge-media', rotulo: 'Pago (liquidado)' };
        default: return { classe: 'badge-baixa', rotulo: status || '-' };
    }
}

function linhaHistorico(c) {
    const dataFmt = c.data ? new Date(String(c.data).slice(0, 10) + 'T12:00:00').toLocaleDateString('pt-BR') : '-';
    const { classe, rotulo } = situacaoCompra(c.status);
    const precisaAprovacao = c.status === 'PENDENTE_APROVACAO' || c.status === 'AGUARDANDO_NIVEL_2';
    return `<tr>
        <td><strong>#${escapeHtml(c.id)}</strong></td>
        <td>${escapeHtml(dataFmt)}</td>
        <td>${escapeHtml(c.fornecedorNome || '-')}</td>
        <td>${escapeHtml(c.solicitanteNome || '-')}</td>
        <td><small>${escapeHtml(c.tipoDocumento || 'MERCADORIA')}${c.notaFiscal ? ' | NF: ' + escapeHtml(c.notaFiscal) : ''}</small></td>
        <td><strong>${formatarMoeda(c.total)}</strong></td>
        <td><span class="badge ${classe}">${escapeHtml(rotulo)}</span></td>
        <td>
            ${precisaAprovacao ? `<button class="acao-tabela-btn acao-tabela-aprovar" data-aprovar-compra="${escapeHtml(c.id)}" type="button">Aprovar alçada</button>` : ''}
            ${c.status === 'CONCLUIDA' ? `<button class="acao-tabela-btn acao-tabela-pagar" data-pagar-compra="${escapeHtml(c.id)}" type="button">Pagar (SoD)</button>` : ''}
            ${c.status === 'PAGO' ? '<span class="texto-quitado">✓ Quitado</span>' : ''}
        </td>
    </tr>`;
}

async function carregarHistoricoCompras() {
    const tbody = document.getElementById('tabelaHistoricoCompras');
    if (!tbody) return;
    try {
        const compras = await listarCompras();
        tbody.innerHTML = compras.map(linhaHistorico).join('')
            || '<tr><td colspan="8" class="estado-vazio">Nenhuma compra realizada ainda.</td></tr>';
    } catch (erro) {
        console.error('Erro ao listar compras:', erro);
        tbody.innerHTML = `<tr><td colspan="8" class="estado-vazio">${escapeHtml(erro.message || 'Erro ao carregar histórico de compras.')}</td></tr>`;
    }
}

/** Aprovar alçada e pagar (delegação: funciona após cada recarga da tabela). */
function configurarAcoesHistorico() {
    const tbody = document.getElementById('tabelaHistoricoCompras');
    if (!tbody) return;
    tbody.addEventListener('click', async (evento) => {
        const aprovar = evento.target.closest('[data-aprovar-compra]');
        const pagar = evento.target.closest('[data-pagar-compra]');
        const botao = aprovar || pagar;
        if (!botao) return;
        botao.disabled = true;
        try {
            const resp = aprovar
                ? await complianceService.aprovarCompra(aprovar.dataset.aprovarCompra)
                : await complianceService.liquidarPagamento(pagar.dataset.pagarCompra);
            notificarSucesso(resp.mensagem || 'Operação registrada com sucesso.');
            // A aprovação final dá entrada no estoque: avisa as outras abas.
            if (aprovar && resp.status === 'CONCLUIDA') avisarMovimentacao('compra');
            await carregarHistoricoCompras();
        } catch (erro) {
            notificarErro(erro.message || 'Não foi possível concluir a operação.');
            botao.disabled = false;
        }
    });
}

async function inicializarTelaCompras(form) {
    const selectFornecedor = document.getElementById('selectFornecedor');
    const inputData = document.getElementById('inputDataCompra');
    const tbody = document.getElementById('corpoItensCompra');
    const elementoTotal = document.getElementById('totalCompra');
    const botaoAdicionar = document.getElementById('btnAdicionarItemCompra');
    const botaoFinalizar = document.getElementById('btnFinalizarCompra');
    const selectTipoDoc = document.getElementById('selectTipoDoc');
    const selectContrato = document.getElementById('selectContrato');
    const inputNotaFiscal = document.getElementById('inputNotaFiscal');

    // Data de hoje (fuso local) já vem preenchida, por conveniência.
    inputData.value = hojeLocal();

    // Carrega fornecedores (pessoas do tipo Fornecedor) e produtos cadastrados.
    const [pessoas, produtos] = await Promise.all([listarPessoas(), listarProdutos()]);
    const fornecedores = pessoas.map(fromPessoaDTO).filter((p) => p.tipoTexto.includes('Fornecedor'));
    const produtosDisponiveis = produtos.map(fromProdutoDTO);
    produtosPorId = new Map(produtosDisponiveis.map((p) => [String(p.id), p]));

    popularSelectFornecedores(selectFornecedor, fornecedores);

    // Prepara a primeira linha e a guarda (limpa) como molde das próximas.
    const primeiraLinha = tbody.rows[0];
    popularSelectProdutos(primeiraLinha.querySelector('[name="produto"]'), produtosDisponiveis);
    aplicarControleValidade(primeiraLinha, null);
    modeloLinha = primeiraLinha.cloneNode(true);

    // Compliance: contratos homologados e histórico de pedidos com alçadas.
    configurarAcoesHistorico();
    void popularSelectContratos(selectContrato);
    void carregarHistoricoCompras();

    // Quando o produto de uma linha muda: sugere o preço de compra e ajusta lote/validade.
    tbody.addEventListener('change', (evento) => {
        if (!evento.target.matches('[name="produto"]')) return;
        const linha = evento.target.closest('tr');
        // O backend envia id numérico, mas o valor do select é string.
        const produto = produtosPorId.get(String(evento.target.value));
        const preco = Number(produto?.precoCompra) || 0;
        const campoPreco = linha.querySelector('.preco-unitario');
        campoPreco.dataset.preco = String(preco);
        campoPreco.textContent = formatarMoeda(preco);
        aplicarControleValidade(linha, produto);
        recalcularSubtotalLinha(linha);
        atualizarTotalGeral(tbody, elementoTotal);
    });

    // Somente a quantidade é editável; o preço vem do produto escolhido.
    tbody.addEventListener('input', (evento) => {
        if (!evento.target.matches('[name="quantidade"]')) return;
        recalcularSubtotalLinha(evento.target.closest('tr'));
        atualizarTotalGeral(tbody, elementoTotal);
    });

    // Enter na quantidade: abre a próxima linha (se esta for a última) e foca o produto.
    tbody.addEventListener('keydown', (evento) => {
        if (evento.key !== 'Enter' || !evento.target.matches('[name="quantidade"]')) return;
        evento.preventDefault();
        const linha = evento.target.closest('tr');
        if (linha === tbody.lastElementChild && evento.target.value) adicionarLinha(tbody);
        linha.nextElementSibling?.querySelector('[name="produto"]').focus();
    });

    // Remover item (delegação: funciona também nas linhas adicionadas depois).
    tbody.addEventListener('click', (evento) => {
        const botaoRemover = evento.target.closest('[data-remover-item]');
        if (!botaoRemover) return;
        removerLinha(tbody, botaoRemover.closest('tr'), elementoTotal);
    });

    botaoAdicionar.addEventListener('click', () => {
        adicionarLinha(tbody).querySelector('[name="produto"]').focus();
    });

    botaoFinalizar.addEventListener('click', async () => {
        limparMensagem(form);

        const fornecedorId = selectFornecedor.value;
        const data = inputData.value;
        const itens = coletarItens(tbody);
        const tipoDocumento = selectTipoDoc?.value || 'MERCADORIA';
        const contratoId = selectContrato?.value || null;
        const notaFiscal = inputNotaFiscal?.value.trim() || '';

        const erroValidacao = validarCompra(fornecedorId, data, itens);
        if (erroValidacao) {
            mostrarErro(form, erroValidacao);
            return;
        }

        iniciarCarregando(botaoFinalizar, 'Registrando...');
        try {
            const compraDTO = toCompraDTO({ fornecedorId, data, itens, tipoDocumento, notaFiscal, contratoId });
            const resp = await criarCompra(compraDTO);
            if (resp.status === 'PENDENTE_APROVACAO') {
                notificarSucesso('Pedido criado! Pelo valor (>= R$ 10.000,00), ele exige aprovação de alçada; o estoque entra após a aprovação final.');
            } else {
                notificarSucesso('Compra registrada e estoque atualizado.');
                avisarMovimentacao('compra'); // estoque.html (outra aba) recarrega na hora
            }
            limparItens(tbody, elementoTotal);
            if (inputNotaFiscal) inputNotaFiscal.value = '';
            await carregarHistoricoCompras();
        } catch (erro) {
            mostrarErro(form, erro.message || 'Não foi possível registrar a compra.');
        } finally {
            pararCarregando(botaoFinalizar);
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    const form = document.getElementById('formCompra');
    if (form) inicializarTelaCompras(form);
});
