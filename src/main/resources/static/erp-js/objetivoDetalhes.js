import * as api from './okrsService.js';
import { listarSquads } from './squadsService.js';
import { listarFuncionarios } from './funcionariosService.js';
import { carregarPermissoes, pode } from './permissoesService.js?v=20261118-submetas-v3';
import { esc, data, dataHora, numero, mensagem, carregando, selo, barra, abrirFormulario, confirmar } from './adminUi.js?v=20261118-submetas-v3';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
const id = new URLSearchParams(location.search).get('id');
let info, funcs = [], squads = [];
const botao = (t, c, fn) => { const b = document.createElement('button'); b.className = `botao ${c}`; b.textContent = t; b.onclick = fn; return b; };
const respOpts = () => [{ valor: '', rotulo: 'Nenhum' }, ...funcs.map((f) => ({ valor: f.id, rotulo: f.nome }))];
const idOuNulo = (v) => (v ? Number(v) : null);
const modoLabel = (modo) => ({ METRICA: 'Métrica numérica', SUBMETAS: 'Checklist de sub-metas', MANUAL: 'Percentual manual' }[modo] || 'Métrica numérica');
const usuarioId = () => Number(info?.usuario?.id || 0);
const ehResponsavel = (r, o) => {
    const ids = [r?.responsavelId, o?.responsavelId].filter((v) => v !== null && v !== undefined && v !== '').map(Number);
    return usuarioId() > 0 && ids.includes(usuarioId());
};
const podeAtuarNoResultado = (r, o) => pode(info, 'okrs.manage') || (pode(info, 'okrs.update') && ehResponsavel(r, o));

async function agir(fn, ok) {
    try { await fn(); mensagem(msg, ok, 'sucesso'); await renderizar(); }
    catch (e) { mensagem(msg, e.message); }
}

function renderSubMetas(r, gerir, atualizar, arquivado) {
    if (r.modoAcompanhamento !== 'SUBMETAS') return '';
    const itens = r.subMetas || [];
    const bloqueado = arquivado || r.status === 'CANCELADO';
    const podeAdicionar = atualizar && !bloqueado;
    const percentual = Math.max(0, Math.min(100, Number(r.progresso || 0)));
    return `<section class="okr-submetas" aria-label="Sub-metas do resultado ${esc(r.titulo)}">
      <div class="okr-submetas-cabecalho">
        <div><strong>Plano de etapas</strong><small>${esc(r.subMetasConcluidas || 0)} de ${esc(r.totalSubMetas || 0)} etapas concluídas</small></div>
        ${podeAdicionar ? `<button type="button" class="botao secundario okr-adicionar-submeta" data-add-submeta="${r.id}">+ Adicionar etapa</button>` : ''}
      </div>
      <div class="okr-submetas-progresso"><div class="progresso" role="progressbar" aria-label="Progresso das sub-metas" aria-valuenow="${percentual}" aria-valuemin="0" aria-valuemax="100"><span style="width:${percentual}%"></span></div><strong>${numero(percentual)}%</strong></div>
      ${!atualizar && !gerir ? '<p class="okr-permissao-ajuda">Visualização somente leitura. Para cadastrar ou marcar etapas, atribua este resultado a você e conceda a permissão <strong>OKRs → Atualizar progresso</strong>, ou solicite acesso de gestor.</p>' : ''}
      ${itens.length ? `<ul class="okr-submetas-lista">${itens.map((sm) => `<li class="${sm.concluida ? 'concluida' : ''}">
        <label class="okr-submeta-item"><input type="checkbox" data-submeta-check="${sm.id}" ${sm.concluida ? 'checked' : ''} ${!atualizar || bloqueado ? 'disabled' : ''}/><span><strong>${esc(sm.titulo)}</strong>${sm.descricao ? `<small>${esc(sm.descricao)}</small>` : ''}</span></label>
        <span class="okr-submeta-status">${sm.concluida ? selo('Concluída', 'ok') : selo('Pendente', 'neutro')}</span>
        ${gerir && !bloqueado ? `<button type="button" class="acao-mini excluir" data-del-submeta="${sm.id}" aria-label="Remover sub-meta ${esc(sm.titulo)}">Remover</button>` : ''}
      </li>`).join('')}</ul>` : podeAdicionar
        ? '<div class="okr-submetas-vazio"><strong>Nenhuma etapa cadastrada ainda</strong><p>Adicione as etapas menores que compõem este resultado. Cada etapa marcada como concluída aumenta automaticamente o percentual.</p></div>'
        : '<div class="okr-submetas-vazio"><strong>Este resultado ainda não possui etapas</strong><p>Peça ao responsável ou a um gestor de OKRs para cadastrar as sub-metas.</p></div>'}
    </section>`;
}

async function renderizar() {
    if (!id) return mensagem(msg, 'Objetivo não informado.');
    carregando(conteudo);
    try {
        const o = await api.buscarObjetivo(id);
        const gerir = pode(info, 'okrs.manage'), arquivado = o.status === 'ARQUIVADO';
        document.getElementById('tituloPagina').textContent = o.titulo;
        acoes.innerHTML = '<a class="botao secundario" href="/okrs.html">← OKRs</a>';
        if (gerir && !arquivado) acoes.append(botao('+ Resultado-chave', 'primario', () => formResultado(o, null)), botao('Editar', 'secundario', () => formObjetivo(o)),
            botao('Arquivar', 'secundario', async () => { if (await confirmar('Arquivar este objetivo? O histórico é preservado.')) agir(() => api.arquivarObjetivo(o.id), 'Objetivo arquivado.'); }));

        const cards = (o.resultados || []).map((r) => {
            const atualizar = podeAtuarNoResultado(r, o);
            const linhaMetricas = r.modoAcompanhamento === 'METRICA'
                ? `<div><small>Inicial</small><strong>${numero(r.valorInicial, 4)} ${esc(r.unidade)}</strong></div><div><small>Atual</small><strong>${numero(r.valorAtual, 4)} ${esc(r.unidade)}</strong></div><div><small>Alvo</small><strong>${numero(r.meta, 4)} ${esc(r.unidade)}</strong></div>`
                : r.modoAcompanhamento === 'MANUAL'
                    ? `<div><small>Tipo de medição</small><strong>Percentual definido manualmente</strong></div><div><small>Progresso informado</small><strong>${numero(r.progresso)}%</strong></div>`
                    : `<div><small>Tipo de medição</small><strong>Conclusão das sub-metas</strong></div><div><small>Etapas concluídas</small><strong>${esc(r.subMetasConcluidas || 0)} / ${esc(r.totalSubMetas || 0)}</strong></div>`;
            const status = r.status === 'CANCELADO' ? selo('Cancelado', 'neutro') : r.status === 'CONCLUIDO' ? selo('Concluído', 'ok') : selo('Em andamento', 'neutro');
            return `<article class="okr-resultado-card">
              <div class="okr-resultado-topo"><div><div class="okr-resultado-titulo"><h3>${esc(r.titulo)}</h3>${status}</div><p>${esc(r.descricao || '')}</p></div><span class="okr-modo">${esc(modoLabel(r.modoAcompanhamento))}</span></div>
              <div class="okr-progresso-linha"><div>${barra(r.progresso)}</div><strong>${numero(r.progresso)}%</strong></div>
              <div class="okr-dados-grid">${linhaMetricas}<div><small>Prazo</small><strong>${data(r.prazo)}</strong></div><div><small>Responsável</small><strong>${esc(r.responsavelNome || '—')}</strong></div></div>
              ${renderSubMetas(r, gerir, atualizar, arquivado)}
              <div class="okr-resultado-acoes"><button type="button" class="acao-mini" data-hist="${r.id}">Histórico</button>
                ${!arquivado && r.status !== 'CANCELADO' && atualizar && r.modoAcompanhamento === 'METRICA' ? `<button type="button" class="acao-mini" data-prog="${r.id}">Atualizar valor</button>` : ''}
                ${!arquivado && r.status !== 'CANCELADO' && atualizar && r.modoAcompanhamento === 'MANUAL' ? `<button type="button" class="acao-mini" data-percent="${r.id}">Definir percentual</button>` : ''}
                ${!arquivado && r.status !== 'CANCELADO' && gerir ? `<button type="button" class="acao-mini" data-edit="${r.id}">Editar</button><button type="button" class="acao-mini excluir" data-canc="${r.id}">Cancelar</button>` : ''}
              </div>
            </article>`;
        }).join('');

        conteudo.innerHTML = `<p>${esc(o.descricao)}</p><div class="detalhe-lista">
          <div><small>Situação</small>${o.atrasado ? selo('Atrasado', 'erro') : selo(o.status === 'ATIVO' ? 'Ativo' : o.status === 'CONCLUIDO' ? 'Concluído' : 'Arquivado', o.status === 'ATIVO' ? 'ok' : 'neutro')}</div>
          <div><small>Período</small><strong>${data(o.periodoInicio)} a ${data(o.periodoFim)}</strong></div>
          <div><small>Squad</small><strong>${esc(o.squadNome || '—')}</strong></div><div><small>Responsável</small><strong>${esc(o.responsavelNome || '—')}</strong></div>
          <div><small>Progresso geral</small>${barra(o.progresso)}</div></div>
          <h2>Resultados-chave</h2>${cards ? `<div class="okr-resultados-lista">${cards}</div>` : '<p class="estado-vazio">Nenhum resultado-chave cadastrado.</p>'}`;

        const kr = (resultId) => o.resultados.find((r) => String(r.id) === String(resultId));
        conteudo.querySelectorAll('[data-hist]').forEach((b) => b.onclick = () => historico(kr(b.dataset.hist)));
        conteudo.querySelectorAll('[data-prog]').forEach((b) => b.onclick = () => progresso(kr(b.dataset.prog)));
        conteudo.querySelectorAll('[data-percent]').forEach((b) => b.onclick = () => percentualManual(kr(b.dataset.percent)));
        conteudo.querySelectorAll('[data-edit]').forEach((b) => b.onclick = () => formResultado(o, kr(b.dataset.edit)));
        conteudo.querySelectorAll('[data-canc]').forEach((b) => b.onclick = async () => { if (await confirmar('Cancelar este resultado-chave? O histórico é preservado.')) agir(() => api.cancelarResultado(b.dataset.canc), 'Resultado cancelado.'); });
        conteudo.querySelectorAll('[data-add-submeta]').forEach((b) => b.onclick = () => adicionarSubMeta(kr(b.dataset.addSubmeta)));
        conteudo.querySelectorAll('[data-del-submeta]').forEach((b) => b.onclick = async () => { if (await confirmar('Remover esta sub-meta? O percentual será recalculado.')) agir(() => api.removerSubMeta(b.dataset.delSubmeta), 'Sub-meta removida e progresso recalculado.'); });
        conteudo.querySelectorAll('[data-submeta-check]').forEach((input) => input.addEventListener('change', async () => {
            const valorSelecionado = input.checked;
            input.disabled = true;
            try {
                await api.atualizarConclusaoSubMeta(input.dataset.submetaCheck, valorSelecionado);
                mensagem(msg, 'Sub-meta atualizada. O percentual foi recalculado.', 'sucesso');
                await renderizar();
            } catch (e) {
                input.checked = !valorSelecionado;
                input.disabled = false;
                mensagem(msg, e.message);
            }
        }));
    } catch (e) { mensagem(msg, e.message); conteudo.innerHTML = ''; }
}

async function formObjetivo(o) {
    const ok = await abrirFormulario({ titulo: 'Editar objetivo', campos: [
        { nome: 'titulo', rotulo: 'Título', valor: o.titulo, obrigatorio: true }, { nome: 'descricao', rotulo: 'Descrição', tipo: 'textarea', valor: o.descricao },
        { nome: 'squadId', rotulo: 'Squad', tipo: 'select', valor: o.squadId ?? '', opcoes: [{ valor: '', rotulo: 'Nenhuma' }, ...squads.map((s) => ({ valor: s.id, rotulo: s.nome }))] },
        { nome: 'responsavelId', rotulo: 'Responsável', tipo: 'select', valor: o.responsavelId ?? '', opcoes: respOpts() },
        { nome: 'periodoInicio', rotulo: 'Início', tipo: 'date', valor: String(o.periodoInicio).slice(0, 10), obrigatorio: true }, { nome: 'periodoFim', rotulo: 'Fim', tipo: 'date', valor: String(o.periodoFim).slice(0, 10), obrigatorio: true }],
        salvar: (v) => api.atualizarObjetivo(o.id, { ...v, squadId: idOuNulo(v.squadId), responsavelId: idOuNulo(v.responsavelId) }) });
    if (ok) { mensagem(msg, 'Objetivo atualizado.', 'sucesso'); renderizar(); }
}

async function formResultado(o, r) {
    const ok = await abrirFormulario({ titulo: r ? 'Editar resultado-chave' : 'Novo resultado-chave', campos: [
        { nome: 'titulo', rotulo: 'Título', valor: r?.titulo, obrigatorio: true },
        { nome: 'descricao', rotulo: 'Descrição', tipo: 'textarea', valor: r?.descricao },
        { nome: 'modoAcompanhamento', rotulo: 'Como deseja medir esta meta?', tipo: 'select', valor: r?.modoAcompanhamento || 'METRICA', opcoes: [
            { valor: 'METRICA', rotulo: 'Métrica numérica (valor inicial e alvo)' },
            { valor: 'SUBMETAS', rotulo: 'Sub-metas (marcar etapas concluídas)' },
            { valor: 'MANUAL', rotulo: 'Percentual manual (definir de 0% a 100%)' }] },
        { nome: 'valorInicial', rotulo: 'Valor inicial', tipo: 'number', passo: 'any', valor: r?.valorInicial ?? 0, ajuda: 'Ponto de partida da métrica.', mostrarQuando: { campo: 'modoAcompanhamento', valor: 'METRICA' } },
        { nome: 'meta', rotulo: 'Valor-alvo', tipo: 'number', passo: 'any', valor: r?.modoAcompanhamento === 'METRICA' ? r.meta : '', ajuda: 'Valor que deseja alcançar.', mostrarQuando: { campo: 'modoAcompanhamento', valor: 'METRICA' } },
        { nome: 'progressoManual', rotulo: 'Percentual inicial (0 a 100)', tipo: 'number', passo: '0.01', valor: r?.progresso ?? 0, ajuda: 'Use este modo quando a avaliação depender do julgamento do responsável.', mostrarQuando: { campo: 'modoAcompanhamento', valor: 'MANUAL' } },
        { nome: 'unidade', rotulo: 'Unidade de medida', valor: r?.unidade, ajuda: 'Ex.: R$, %, clientes.', mostrarQuando: { campo: 'modoAcompanhamento', valor: 'METRICA' } },
        { nome: 'responsavelId', rotulo: 'Responsável', tipo: 'select', valor: r?.responsavelId ?? '', opcoes: respOpts() },
        { nome: 'prazo', rotulo: 'Prazo', tipo: 'date', valor: r ? String(r.prazo).slice(0, 10) : '', obrigatorio: true, ajuda: 'No modo Sub-metas, depois de salvar, use o botão Adicionar etapa para montar o checklist.' }],
        salvar: (v) => {
            const modo = v.modoAcompanhamento;
            const c = { ...v, responsavelId: idOuNulo(v.responsavelId) };
            if (modo === 'METRICA') {
                if (c.valorInicial === '' || c.meta === '') throw new Error('Informe o valor inicial e a meta numérica.');
            } else { delete c.valorInicial; delete c.meta; }
            if (modo === 'MANUAL') c.progressoManual = Number(c.progressoManual || 0);
            return r ? api.atualizarResultado(r.id, c) : api.criarResultado(o.id, c);
        } });
    if (ok) { mensagem(msg, 'Resultado-chave salvo.', 'sucesso'); renderizar(); }
}

async function adicionarSubMeta(r) {
    const ok = await abrirFormulario({ titulo: `Nova sub-meta: ${r.titulo}`, textoSalvar: 'Adicionar etapa', campos: [
        { nome: 'titulo', rotulo: 'Nome da etapa', obrigatorio: true },
        { nome: 'descricao', rotulo: 'Descrição (opcional)', tipo: 'textarea' },
        { nome: 'ordem', rotulo: 'Ordem de exibição', tipo: 'number', valor: (r.subMetas?.length || 0) + 1, passo: '1' }],
        salvar: (v) => api.criarSubMeta(r.id, { ...v, ordem: Number(v.ordem || 0) }) });
    if (ok) { mensagem(msg, 'Sub-meta adicionada. Marque cada etapa conforme for concluída.', 'sucesso'); renderizar(); }
}

async function progresso(r) {
    const ok = await abrirFormulario({ titulo: `Atualizar: ${r.titulo}`, campos: [
        { nome: 'valorAtual', rotulo: `Novo valor atual (${r.unidade || 'unidade'})`, tipo: 'number', passo: 'any', valor: r.valorAtual, obrigatorio: true },
        { nome: 'comentario', rotulo: 'Comentário', tipo: 'textarea', ajuda: 'Registrado no histórico.' }],
        salvar: (v) => api.atualizarProgresso(r.id, v.valorAtual, v.comentario) });
    if (ok) { mensagem(msg, 'Progresso atualizado e registrado no histórico.', 'sucesso'); renderizar(); }
}

async function percentualManual(r) {
    const ok = await abrirFormulario({ titulo: `Definir progresso: ${r.titulo}`, campos: [
        { nome: 'percentual', rotulo: 'Percentual de atendimento (0 a 100)', tipo: 'number', passo: '0.01', valor: r.progresso, obrigatorio: true },
        { nome: 'comentario', rotulo: 'Justificativa / observação', tipo: 'textarea', ajuda: 'A alteração fica registrada no histórico.' }],
        salvar: (v) => {
            const n = Number(v.percentual);
            if (!Number.isFinite(n) || n < 0 || n > 100) throw new Error('Informe um percentual entre 0 e 100.');
            return api.atualizarPercentual(r.id, n, v.comentario);
        } });
    if (ok) { mensagem(msg, 'Percentual atualizado e registrado no histórico.', 'sucesso'); renderizar(); }
}

async function historico(r) {
    try {
        const h = await api.historicoResultado(r.id);
        const modoHistorico = r.modoAcompanhamento === 'MANUAL' ? '%' : (r.unidade || 'valor');
        const dlg = document.createElement('dialog'); dlg.className = 'historico-dialog dialogo-form';
        dlg.innerHTML = `<div class="historico-topo"><h2>Histórico: ${esc(r.titulo)}</h2></div>${h.length ? `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Quando</th><th>De (${esc(modoHistorico)})</th><th>Para (${esc(modoHistorico)})</th><th>Quem</th><th>Comentário</th></tr></thead><tbody>${h.map((x) => `<tr><td>${dataHora(x.criadoEm)}</td><td>${numero(x.valorAnterior, 4)}</td><td>${numero(x.valorNovo, 4)}</td><td>${esc(x.usuarioNome || '—')}</td><td>${esc(x.comentario)}</td></tr>`).join('')}</tbody></table></div>` : '<p class="estado-vazio">Nenhuma atualização registrada.</p>'}<div class="botoes"><button class="botao secundario" type="button">Fechar</button></div>`;
        document.body.appendChild(dlg); dlg.querySelector('button').onclick = () => { dlg.close(); dlg.remove(); }; dlg.showModal();
    } catch (e) { mensagem(msg, e.message); }
}

(async () => {
    info = await carregarPermissoes();
    try { squads = await listarSquads(); } catch { squads = []; }
    try { funcs = await listarFuncionarios({ status: 'ativos' }); } catch { funcs = []; }
    renderizar();
})();
