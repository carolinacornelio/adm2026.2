/**
 * compras.js
 * ---------------------------------------------------------------------------
 * Controller de compras.html com controles de alçada e compliance.
 * ---------------------------------------------------------------------------
 */

import { listarPessoas } from './pessoasService.js';
import { listarProdutos } from './produtosService.js';
import { criarCompra, listarCompras } from './comprasService.js';
import { complianceService } from './complianceService.js';
import { estaAutenticado } from './authService.js';
import { fromPessoaDTO, fromProdutoDTO, toCompraDTO } from './dto.js';
import { iniciarCarregando, pararCarregando, mostrarErro, limparMensagem, escapeHtml, notificarSucesso, notificarErro } from './uiService.js';

let produtosDisponiveis = [];

function formatarMoeda(valor) {
    return Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function popularSelectFornecedores(selectElement, fornecedores) {
    if (!selectElement) return;
    const opcoes = (fornecedores || [])
        .map((f) => `<option value="${escapeHtml(f.id)}">${escapeHtml(f.nome)}</option>`)
        .join('');
    selectElement.innerHTML = '<option value="">Selecione um fornecedor</option>' + opcoes;
}

function popularSelectProdutos(selectElement) {
    if (!selectElement) return;
    const opcoes = (produtosDisponiveis || [])
        .map((p) => `<option value="${escapeHtml(p.id)}">${escapeHtml(p.nome)} (${escapeHtml(p.codigo || p.id)})</option>`)
        .join('');
    selectElement.innerHTML = '<option value="">Selecione um produto</option>' + opcoes;
}

async function popularSelectContratos(selectElement) {
    try {
        const contratos = await complianceService.listarContratos();
        const contratosAprovados = contratos.filter(c => c.status === 'APROVADO');
        const opcoes = contratosAprovados
            .map(c => `<option value="${c.id}">${escapeHtml(c.numeroContrato)} - ${escapeHtml(c.fornecedorNome)} (${formatarMoeda(c.valorTotal)})</option>`)
            .join('');
        selectElement.innerHTML = '<option value="">Selecione um contrato homologado</option>' + opcoes;
    } catch (e) {
        console.error('Erro ao listar contratos homologados:', e);
    }
}

function recalcularSubtotalLinha(linha) {
    const quantidade = Number(linha.querySelector('[name="quantidade"]').value) || 0;
    const preco = Number(linha.querySelector('.preco-unitario').dataset.preco) || 0;
    const subtotal = quantidade * preco;
    linha.querySelector('.coluna-subtotal').textContent = formatarMoeda(subtotal);
    return subtotal;
}

function recalcularTotalGeral(tbody, elementoTotal) {
    let total = 0;
    tbody.querySelectorAll('tr').forEach((linha) => {
        total += recalcularSubtotalLinha(linha);
    });
    elementoTotal.textContent = formatarMoeda(total);
}

function adicionarLinha(tbody) {
    const ultimaLinha = tbody.querySelector('tr:last-child');
    const novaLinha = ultimaLinha.cloneNode(true);

    novaLinha.querySelector('[name="produto"]').value = '';
    novaLinha.querySelector('[name="quantidade"]').value = '';
    novaLinha.querySelector('.preco-unitario').dataset.preco = '0';
    novaLinha.querySelector('.preco-unitario').textContent = formatarMoeda(0);
    novaLinha.querySelector('.coluna-subtotal').textContent = formatarMoeda(0);

    tbody.appendChild(novaLinha);
}

function removerLinha(tbody, linha, elementoTotal) {
    const linhas = tbody.querySelectorAll('tr');
    if (linhas.length === 1) {
        linha.querySelector('[name="produto"]').value = '';
        linha.querySelector('[name="quantidade"]').value = '';
        linha.querySelector('.preco-unitario').dataset.preco = '0';
        linha.querySelector('.preco-unitario').textContent = formatarMoeda(0);
    } else {
        linha.remove();
    }
    recalcularTotalGeral(tbody, elementoTotal);
}

function coletarItens(tbody) {
    const itens = [];
    tbody.querySelectorAll('tr').forEach((linha) => {
        const produtoId = linha.querySelector('[name="produto"]').value;
        const quantidade = Number(linha.querySelector('[name="quantidade"]').value);
        const precoUnitario = Number(linha.querySelector('.preco-unitario').dataset.preco);
        if (produtoId && quantidade > 0) {
            itens.push({ produtoId, quantidade, precoUnitario });
        }
    });
    return itens;
}

function validarCompra(fornecedorId, data, itens) {
    if (!fornecedorId) return 'Selecione um fornecedor.';
    if (!data) return 'Informe a data da compra.';
    if (itens.length === 0) return 'Adicione ao menos um produto com quantidade válida.';
    return null;
}

async function carregarHistoricoCompras() {
    const tbody = document.getElementById('tabelaHistoricoCompras');
    if (!tbody) return;

    try {
        const compras = await listarCompras();
        if (compras.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" style="text-align: center; color: var(--cinza);">Nenhuma compra realizada ainda.</td></tr>`;
            return;
        }

        tbody.innerHTML = compras.map(c => {
            const dataFmt = c.data ? new Date(c.data + 'T00:00:00').toLocaleDateString('pt-BR') : '-';
            const totalFmt = formatarMoeda(c.total);

            let badgeStatus = 'badge-baixa';
            let rotuloStatus = c.status;

            if (c.status === 'PENDENTE_APROVACAO') {
                badgeStatus = 'badge-alta';
                rotuloStatus = 'Aguardando Aprovação';
            } else if (c.status === 'AGUARDANDO_NIVEL_2') {
                badgeStatus = 'badge-critica';
                rotuloStatus = 'Aguardando Aprovação';
            } else if (c.status === 'CONCLUIDA') {
                badgeStatus = 'badge-conforme';
                rotuloStatus = 'Aprovada';
            } else if (c.status === 'PAGO') {
                badgeStatus = 'badge-media';
                rotuloStatus = 'Pago (Liquidado)';
            }

            const precisaAprovacao = c.status === 'PENDENTE_APROVACAO' || c.status === 'AGUARDANDO_NIVEL_2';
            const prontoParaPagar = c.status === 'CONCLUIDA';

            return `
                <tr>
                    <td><strong>#${c.id}</strong></td>
                    <td>${dataFmt}</td>
                    <td>${escapeHtml(c.fornecedorNome || 'Fornecedor #' + c.fornecedorId)}</td>
                    <td>${escapeHtml(c.solicitanteNome || '-')}</td>
                    <td><small>${escapeHtml(c.tipoDocumento || 'MERCADORIA')} ${c.notaFiscal ? ' | NF: ' + escapeHtml(c.notaFiscal) : ''}</small></td>
                    <td><strong>${totalFmt}</strong></td>
                    <td><span class="badge ${badgeStatus}">${rotuloStatus}</span></td>
                    <td>
                        ${precisaAprovacao ? `
                            <button class="acao-tabela-btn acao-tabela-aprovar" data-aprovar-compra="${c.id}" type="button">Aprovar Alçada</button>
                        ` : ''}
                        ${prontoParaPagar ? `
                            <button class="acao-tabela-btn" style="background: var(--azul); color: white;" data-pagar-compra="${c.id}" type="button">Pagar (SoD)</button>
                        ` : ''}
                        ${c.status === 'PAGO' ? '<span style="color: var(--verde); font-weight: 600;">✓ Quitado</span>' : ''}
                    </td>
                </tr>
            `;
        }).join('');

        tbody.querySelectorAll('[data-aprovar-compra]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-aprovar-compra');
                try {
                    const resp = await complianceService.aprovarCompra(id);
                    notificarSucesso(resp.mensagem || 'Aprovação registrada com sucesso.');
                    await carregarHistoricoCompras();
                } catch (err) {
                    notificarErro(err.message || 'Erro ao aprovar compra.');
                }
            });
        });

        tbody.querySelectorAll('[data-pagar-compra]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-pagar-compra');
                try {
                    const resp = await complianceService.liquidarPagamento(id);
                    notificarSucesso(resp.mensagem || 'Pagamento liquidado com sucesso.');
                    await carregarHistoricoCompras();
                } catch (err) {
                    notificarErro(err.message || 'Erro ao processar pagamento.');
                }
            });
        });
    } catch (e) {
        console.error('Erro ao listar compras:', e);
        tbody.innerHTML = `<tr><td colspan="8" style="text-align: center; color: var(--vermelho);">${escapeHtml(e.message || 'Erro ao carregar histórico de compras.')}</td></tr>`;
    }
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

    inputData.value = new Date().toISOString().split('T')[0];

    const [resPessoas, resProdutos] = await Promise.allSettled([listarPessoas(), listarProdutos()]);

    if (resPessoas.status === 'fulfilled') {
        const fornecedores = (resPessoas.value || [])
            .map(fromPessoaDTO)
            .filter((p) => p && p.tipoTexto && p.tipoTexto.toLowerCase().includes('fornecedor'));
        popularSelectFornecedores(selectFornecedor, fornecedores);
    } else {
        console.error('Erro ao carregar fornecedores:', resPessoas.reason);
        mostrarErro(form, resPessoas.reason?.message || 'Não foi possível carregar a lista de fornecedores.');
    }

    if (resProdutos.status === 'fulfilled') {
        produtosDisponiveis = (resProdutos.value || []).map(fromProdutoDTO);
        popularSelectProdutos(tbody.querySelector('[name="produto"]'));
    } else {
        console.error('Erro ao carregar produtos:', resProdutos.reason);
        mostrarErro(form, resProdutos.reason?.message || 'Não foi possível carregar a lista de produtos.');
    }

    try {
        await popularSelectContratos(selectContrato);
    } catch (e) {
        console.error('Erro ao listar contratos homologados:', e);
    }

    try {
        await carregarHistoricoCompras();
    } catch (e) {
        console.error('Erro ao carregar histórico de compras:', e);
    }

    tbody.addEventListener('change', (evento) => {
        if (evento.target.matches('[name="produto"]')) {
            const linha = evento.target.closest('tr');
            const produto = produtosDisponiveis.find(
                (p) => String(p.id) === String(evento.target.value)
            );
            const preco = Number(produto?.precoCompra) || 0;
            const campoPreco = linha.querySelector('.preco-unitario');
            campoPreco.dataset.preco = String(preco);
            campoPreco.textContent = formatarMoeda(preco);
            recalcularTotalGeral(tbody, elementoTotal);
        }
    });

    tbody.addEventListener('input', (evento) => {
        if (evento.target.matches('[name="quantidade"]')) {
            recalcularTotalGeral(tbody, elementoTotal);
        }
    });

    tbody.addEventListener('click', (evento) => {
        const botaoRemover = evento.target.closest('[data-remover-item]');
        if (!botaoRemover) return;
        removerLinha(tbody, botaoRemover.closest('tr'), elementoTotal);
    });

    botaoAdicionar.addEventListener('click', () => {
        adicionarLinha(tbody);
        popularSelectProdutos(tbody.querySelector('tr:last-child [name="produto"]'));
    });

    botaoFinalizar.addEventListener('click', async () => {
        limparMensagem(form);

        const fornecedorId = selectFornecedor.value;
        const data = inputData.value;
        const itens = coletarItens(tbody);
        const tipoDocumento = selectTipoDoc?.value || 'MERCADORIA';
        const contratoId = selectContrato?.value || null;
        const notaFiscal = inputNotaFiscal?.value.trim() || '';

        const erroValidacao = validarCompra(fornecedorId, data, itens, tipoDocumento, contratoId);
        if (erroValidacao) {
            mostrarErro(form, erroValidacao);
            return;
        }

        iniciarCarregando(botaoFinalizar, 'Registrando...');
        try {
            const compraDTO = toCompraDTO({ fornecedorId, data, itens, tipoDocumento, notaFiscal, contratoId });
            const resp = await criarCompra(compraDTO);
            
            if (resp.status === 'PENDENTE_APROVACAO') {
                notificarSucesso('Pedido criado com sucesso! Devido ao valor (>= R$ 10.000,00), a transação exige aprovação de alçada dupla.');
            } else {
                notificarSucesso('Compra registrada com sucesso!');
            }

            await carregarHistoricoCompras();
            // Limpa itens
            tbody.innerHTML = `<tr data-item="template"><td><select name="produto"><option value="">Selecione um produto</option></select></td><td><input min="1" name="quantidade" type="number" value=""/></td><td class="preco-unitario" data-preco="0">R$ 0,00</td><td class="coluna-subtotal">R$ 0,00</td><td><button class="botao-remover-item" data-remover-item title="Remover item" type="button">❌</button></td></tr>`;
            popularSelectProdutos(tbody.querySelector('[name="produto"]'));
            recalcularTotalGeral(tbody, elementoTotal);
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
