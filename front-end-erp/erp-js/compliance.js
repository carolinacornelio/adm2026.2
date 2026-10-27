import { complianceService as api } from './complianceService.js';
import { estaAutenticado } from './authService.js';
import { escapeHtml as e } from './uiService.js';
import { formatarData, formatarDataHora } from './timeUtils.js';
import { avisarMovimentacao } from './estoqueSync.js';

const $ = seletor => document.querySelector(seletor);
const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const dinheiro = valor => moeda.format(Number(valor) || 0);
const perfis = { padrao:'Padrão', comprador:'Comprador', gerente_compras:'Gerente de Compras', diretor:'Diretor', compliance:'Compliance', financeiro:'Financeiro', admin:'Administrador' };
const rotulos = { PENDENTE_APROVACAO:'Aguardando nível 1', AGUARDANDO_NIVEL_2:'Aguardando nível 2', concluida:'Concluída', paga:'Paga', cancelada:'Cancelada', APROVADO:'Aprovado', PENDENTE_COMPLIANCE:'Aguardando homologação', PENDENTE:'Pendente', REVISADO:'Revisado', REJEITADO:'Rejeitado' };
let contexto, ocupado = false;
const dados = {}, falhas = {};

function aviso(texto, erro = false) {
    $('#mensagemCompliance').textContent = texto;
    $('#mensagemCompliance').hidden = !texto;
    $('#mensagemCompliance').dataset.erro = String(erro);
}
function estado(valor) {
    const classe = ['APROVADO','concluida','paga','REVISADO'].includes(valor) ? 'ok' : ['cancelada','REJEITADO'].includes(valor) ? 'neutro' : 'pendente';
    return `<span class="compliance-status ${classe}">${e(rotulos[valor] || valor)}</span>`;
}
function documento(valor) {
    try {
        const url = new URL(valor);
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return 'Link indisponível';
        return `<a class="compliance-link" href="${e(url.href)}" target="_blank" rel="noopener noreferrer">Ver documento</a>`;
    } catch { return 'Sem documento'; }
}
function botao(acao, id, texto) { return `<button type="button" class="acao-mini" data-acao="${acao}" data-id="${Number(id)}">${texto}</button>`; }
function tabela(id, chave, colunas, desenhar, vazio) {
    const linhas = dados[chave] || [];
    $(id).innerHTML = falhas[chave]
        ? `<tr><td colspan="${colunas}" class="estado-vazio">${e(falhas[chave])}</td></tr>`
        : linhas.length ? linhas.map(desenhar).join('') : `<tr><td colspan="${colunas}" class="estado-vazio">${e(vazio)}</td></tr>`;
}
function opcoesPerfil(valor) { return Object.entries(perfis).map(([id, nome]) => `<option value="${id}" ${id === valor ? 'selected' : ''}>${nome}</option>`).join(''); }
function preencherFornecedores(id) {
    const select = $(id), anterior = select.value;
    select.innerHTML = '<option value="">Selecione um fornecedor</option>' + (dados.fornecedores || []).map(f => `<option value="${Number(f.id)}">${e(f.nome)}${f.dadosBancariosBloqueados ? ' (alteração bancária pendente)' : ''}</option>`).join('');
    select.value = anterior;
}
function renderizar() {
    $('#perfilCompliance').textContent = `Perfil atual: ${perfis[contexto.perfil] || contexto.perfil}`;
    $('#novoContrato').hidden = !contexto.podeCriarContrato || Boolean(falhas.fornecedores);
    $('#novaAlteracaoBanco').hidden = !contexto.podeSolicitarBanco || Boolean(falhas.fornecedores);
    $('#novoUsuario').hidden = !contexto.podeGerenciar;
    preencherFornecedores('#fornecedorContrato'); preencherFornecedores('#fornecedorBanco');
    $('#totalComprasPendentes').textContent = falhas.compras ? '—' : (dados.compras || []).filter(c => ['PENDENTE_APROVACAO','AGUARDANDO_NIVEL_2'].includes(c.status)).length;
    $('#totalContratosPendentes').textContent = falhas.contratos ? '—' : (dados.contratos || []).filter(c => c.status === 'PENDENTE_COMPLIANCE').length;
    $('#totalPendencias4Olhos').textContent = falhas.banco ? '—' : (dados.banco || []).length;
    $('#totalTransacoesRisco').textContent = !contexto.podeAuditar || falhas.alertas ? '—' : (dados.alertas || []).filter(a => a.status === 'PENDENTE').length;
    tabela('#tabelaComprasCompliance', 'compras', 6, compra => {
        const propria = Number(compra.solicitanteId) === contexto.usuarioId;
        const n1 = compra.status === 'PENDENTE_APROVACAO', n2 = compra.status === 'AGUARDANDO_NIVEL_2';
        const aprova = !propria && (n1 && contexto.podeNivel1 || n2 && contexto.podeNivel2 && Number(compra.aprovadorNivel1) !== contexto.usuarioId);
        const paga = compra.status.toLowerCase() === 'concluida' && compra.contaStatus === 'pendente' && contexto.podePagar && !propria && Number(compra.aprovadorNivel1) !== contexto.usuarioId && Number(compra.aprovadorNivel2) !== contexto.usuarioId;
        return `<tr><td>#${Number(compra.id)}<small>${compra.tipoDocumento === 'SERVICO' ? 'Serviço' : 'Mercadoria'} ${e(compra.notaFiscal || '')}</small></td><td>${e(formatarData(compra.data))}</td><td>${e(compra.fornecedorNome)}<small>Solicitante: ${e(compra.solicitanteNome || 'Legado')}</small></td><td>${dinheiro(compra.total)}</td><td>${estado(compra.status)}${compra.contaStatus ? `<small>Conta: ${e(compra.contaStatus)}</small>` : ''}</td><td><div class="acoes-tabela">${botao('detalhe', compra.id, 'Ver itens')}${aprova ? botao('aprovar-compra', compra.id, n1 ? 'Aprovar nível 1' : 'Aprovar nível 2') : ''}${paga ? botao('pagar', compra.id, 'Registrar pagamento') : ''}${(n1 || n2) && (propria || contexto.podeGerenciar) ? botao('cancelar', compra.id, 'Cancelar pedido') : ''}</div></td></tr>`;
    }, 'Nenhuma compra cadastrada.');
    tabela('#tabelaContratos', 'contratos', 7, contrato => `<tr><td>${e(contrato.numeroContrato)}</td><td>${e(contrato.fornecedorNome)}</td><td>${e(contrato.objeto)}<small>${e(formatarData(contrato.dataInicio, 'Sem início'))} a ${e(formatarData(contrato.dataFim, 'sem fim'))}</small></td><td>${dinheiro(contrato.valorTotal)}</td><td>${estado(contrato.status)}</td><td>${documento(contrato.arquivoUrl)}</td><td>${contrato.status === 'PENDENTE_COMPLIANCE' && contexto.podeHomologar && Number(contrato.criadoPor) !== contexto.usuarioId ? botao('homologar', contrato.id, 'Homologar') : '—'}</td></tr>`, 'Nenhum contrato cadastrado.');
    tabela('#tabela4Olhos', 'banco', 5, pedido => `<tr><td>#${Number(pedido.id)}<small>${e(formatarDataHora(pedido.dataSolicitacao))}</small></td><td>${e(pedido.fornecedorNome)}<small>${e(pedido.solicitanteNome)}</small></td><td>Banco: ${e(pedido.banco || '—')} · Agência: ${e(pedido.agencia || '—')}<small>Conta: ${e(pedido.conta || '—')} · Pix: ${e(pedido.chavePix || '—')}</small></td><td>${documento(pedido.arquivoComprobatorioUrl)}</td><td>${contexto.podeHomologar && Number(pedido.solicitanteId) !== contexto.usuarioId ? `<div class="acoes-tabela">${botao('aprovar-banco', pedido.id, 'Aprovar alteração')}${botao('rejeitar-banco', pedido.id, 'Rejeitar alteração')}</div>` : 'Aguardando outra pessoa habilitada'}</td></tr>`, contexto.podeSolicitarBanco ? 'Nenhuma alteração bancária pendente visível.' : 'Seu perfil não acessa dados bancários.');
    tabela('#tabelaTransacoesRisco', 'alertas', 6, alerta => `<tr><td>${e(formatarDataHora(alerta.dataHora))}<small>${e(alerta.usuarioNome)}</small></td><td>${e(alerta.documentoReferencia)}</td><td>${dinheiro(alerta.valorTransacao)}</td><td>${e(alerta.motivoAlerta)}<small>${e(alerta.nivelSeveridade)}</small></td><td>${e(alerta.detalhes)}</td><td>${estado(alerta.status)}${alerta.status === 'PENDENTE' ? botao('revisar', alerta.id, 'Registrar revisão') : `<small>${e(alerta.justificativa || '')}</small>`}</td></tr>`, contexto.podeAuditar ? 'Nenhum registro de risco nesta consulta.' : 'Acesso restrito a Administrador, Diretor e Compliance.');
    tabela('#tabelaAuditoriaSoD', 'equipe', 4, pessoa => `<tr><td>${e(pessoa.nome)}<small>${e(pessoa.email)}</small></td><td>${contexto.podeGerenciar ? `<label class="ajuda-campo" for="perfil-${Number(pessoa.id)}">Perfil de ${e(pessoa.nome)}</label><select id="perfil-${Number(pessoa.id)}">${opcoesPerfil(pessoa.perfil)}</select>` : e(perfis[pessoa.perfil] || pessoa.perfil)}</td><td>${e(pessoa.diagnostico)}</td><td>${contexto.podeGerenciar ? botao('perfil', pessoa.id, 'Salvar perfil') : 'Somente leitura'}</td></tr>`, contexto.podeAuditar ? 'Nenhum usuário encontrado.' : 'Acesso restrito a Administrador, Diretor e Compliance.');
}
async function carregar() {
    contexto = await api.contexto();
    const tarefas = { compras:api.compras, contratos:api.contratos, fornecedores:api.fornecedores, banco:contexto.podeSolicitarBanco ? api.alteracoes : async () => [], alertas:contexto.podeAuditar ? api.alertas : async () => [], equipe:contexto.podeAuditar ? api.auditar : async () => [] };
    const chaves = Object.keys(tarefas);
    const resultados = await Promise.allSettled(chaves.map(chave => tarefas[chave]()));
    resultados.forEach((resultado, indice) => {
        const chave = chaves[indice];
        if (resultado.status === 'fulfilled') { dados[chave] = resultado.value; delete falhas[chave]; }
        else { dados[chave] = []; falhas[chave] = resultado.reason.message || 'Não foi possível carregar estes registros.'; }
    });
    renderizar();
    return Object.values(falhas);
}
async function executar(botaoAlvo, operacao, mensagem) {
    if (ocupado) return;
    ocupado = true; botaoAlvo.disabled = true; $('#atualizarCompliance').disabled = true;
    $('.compliance-pagina').setAttribute('aria-busy', 'true');
    let confirmado = false;
    try {
        await operacao(); confirmado = true;
        const erros = await carregar();
        aviso(mensagem + (erros.length ? ' Alguns registros não puderam ser atualizados; use Atualizar painel.' : ''), erros.length > 0);
    } catch (erro) { aviso((confirmado ? 'Operação confirmada. Falha ao recarregar o painel: ' : '') + (erro.message || 'Não foi possível concluir a operação.'), true); }
    finally { ocupado = false; botaoAlvo.disabled = false; $('#atualizarCompliance').disabled = false; $('.compliance-pagina').removeAttribute('aria-busy'); }
}
function dadosFormulario(form) { return Object.fromEntries(new FormData(form)); }
function configurarFormularios() {
    $('#perfilColaborador').innerHTML = opcoesPerfil('padrao');
    $('#formContrato').addEventListener('submit', evento => {
        evento.preventDefault(); const form = evento.currentTarget;
        executar(evento.submitter || form.querySelector('[type=submit]'), async () => { const valores = dadosFormulario(form); valores.fornecedorId = Number(valores.fornecedorId); valores.valorTotal = Number(valores.valorTotal); await api.criarContrato(valores); form.reset(); $('#novoContrato').open = false; }, 'Contrato registrado.');
    });
    $('#formBanco').addEventListener('submit', evento => {
        evento.preventDefault(); const form = evento.currentTarget;
        executar(evento.submitter || form.querySelector('[type=submit]'), async () => { const valores = dadosFormulario(form); await api.solicitarBanco(Number(valores.fornecedorId), valores); form.reset(); $('#dadosBancoAtuais').textContent = ''; $('#novaAlteracaoBanco').open = false; }, 'Solicitação registrada. Aguarde a decisão de outra pessoa habilitada.');
    });
    $('#fornecedorBanco').addEventListener('change', evento => {
        const fornecedor = (dados.fornecedores || []).find(f => String(f.id) === evento.target.value);
        for (const campo of ['banco','agencia','conta','chavePix']) $('#formBanco').elements[campo].value = fornecedor?.[campo] || '';
        $('#comprovanteBanco').value = '';
        $('#dadosBancoAtuais').textContent = fornecedor ? `Dados atualmente aprovados de ${fornecedor.nome}. Informe os novos valores e o comprovante.` : '';
    });
    $('#formUsuario').addEventListener('submit', evento => {
        evento.preventDefault(); const form = evento.currentTarget;
        executar(evento.submitter || form.querySelector('[type=submit]'), async () => { await api.criarUsuario(dadosFormulario(form)); form.reset(); $('#novoUsuario').open = false; }, 'Colaborador cadastrado nesta empresa. Ele definirá suas perguntas de segurança no primeiro login.');
    });
    $('#formRevisaoAlerta').addEventListener('submit', evento => {
        evento.preventDefault(); const form = evento.currentTarget, valores = dadosFormulario(form);
        executar(evento.submitter || form.querySelector('[type=submit]'), async () => { await api.revisar(Number(valores.id), valores.justificativa); $('#revisaoCompliance').close(); form.reset(); }, 'Revisão registrada.');
    });
}
async function acao(botaoAlvo) {
    const id = Number(botaoAlvo.dataset.id), tipo = botaoAlvo.dataset.acao;
    if (!Number.isInteger(id) || id <= 0) return;
    if (tipo === 'revisar') { $('#formRevisaoAlerta').reset(); $('#formRevisaoAlerta').elements.id.value = id; $('#revisaoCompliance').showModal(); return; }
    if (tipo === 'detalhe') {
        await executar(botaoAlvo, async () => {
            const compra = await api.detalhe(id);
            $('#tituloDetalhesCompliance').textContent = `Compra #${id} · ${dinheiro(compra.total)}`;
            $('#conteudoDetalhesCompliance').innerHTML = compra.tipoDocumento === 'SERVICO' ? `<p>${e(compra.descricaoServico)}</p><p class="ajuda-campo">Serviço sem movimentação de estoque.</p>` : `<div class="historico-tabela-scroll"><table class="tabela"><thead><tr><th>Produto</th><th>Quantidade</th><th>Preço unitário</th><th>Lote</th><th>Validade</th></tr></thead><tbody>${compra.itens.map(item => `<tr><td>${e(item.nome)}</td><td>${e(item.quantidade)}</td><td>${dinheiro(item.precoUnitario)}</td><td>${e(item.lote || '—')}</td><td>${e(formatarData(item.validade))}</td></tr>`).join('')}</tbody></table></div>`;
            $('#detalhesCompliance').showModal();
        }, 'Detalhes consultados.');
        return;
    }
    const operacoes = {
        'homologar': () => api.homologar(id),
        'aprovar-banco': () => api.decidirBanco(id, true),
        'rejeitar-banco': () => api.decidirBanco(id, false),
        'aprovar-compra': async () => { const resposta = await api.aprovarCompra(id); if (resposta.status === 'concluida') avisarMovimentacao('compra-aprovada'); },
        'pagar': () => api.pagar(id),
        'cancelar': () => api.cancelar(id),
        'perfil': () => api.perfil(id, $(`#perfil-${id}`).value),
    };
    const confirmacoes = { homologar:'Homologar este contrato?', 'aprovar-banco':'Aprovar os novos dados bancários deste fornecedor?', 'rejeitar-banco':'Rejeitar esta alteração bancária?', 'aprovar-compra':'Registrar sua aprovação para esta compra?', pagar:'Confirma que o pagamento foi realizado fora do ERP? Esta ação registra a liquidação.', cancelar:'Cancelar este pedido pendente?', perfil:'Salvar o novo perfil deste usuário?' };
    if (operacoes[tipo] && window.confirm(confirmacoes[tipo])) await executar(botaoAlvo, operacoes[tipo], tipo === 'pagar' ? 'Pagamento registrado.' : 'Operação registrada.');
}
document.addEventListener('DOMContentLoaded', async () => {
    if (!estaAutenticado()) return;
    configurarFormularios();
    document.querySelectorAll('[data-painel]').forEach(botao => botao.addEventListener('click', () => {
        document.querySelectorAll('[data-painel]').forEach(b => b.setAttribute('aria-selected', String(b === botao)));
        document.querySelectorAll('[role=tabpanel]').forEach(p => { p.hidden = p.id !== botao.dataset.painel; });
    }));
    $('.compliance-pagina').addEventListener('click', evento => {
        const fechar = evento.target.closest('[data-fechar]');
        if (fechar) { if (!ocupado) document.getElementById(fechar.dataset.fechar)?.close(); return; }
        const botao = evento.target.closest('[data-acao]');
        if (botao && !botao.disabled && !ocupado && contexto) void acao(botao);
    });
    $('#atualizarCompliance').addEventListener('click', () => executar($('#atualizarCompliance'), async () => {}, 'Painel atualizado.'));
    try { $('#atualizarCompliance').disabled = true; const erros = await carregar(); aviso(erros.join(' '), erros.length > 0); }
    catch (erro) { aviso(erro.message || 'Não foi possível carregar o painel.', true); }
    finally { $('#atualizarCompliance').disabled = false; }
});
