import * as api from './okrsService.js';
import { listarSquads } from './squadsService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, data, dataHora, numero, mensagem, carregando, selo, barra, abrirFormulario, confirmar } from './adminUi.js?v=okrs-4';
import { funcionariosParaOkrs, opcoesResponsaveis } from './objetivoForm.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
const id = new URLSearchParams(location.search).get('id');
let info, busca = 0;
const botao = (t, c, fn) => { const b = document.createElement('button'); b.className = `botao ${c}`; b.textContent = t; b.onclick = fn; return b; };
const idOuNulo = (v) => (v ? Number(v) : null);
const modoLabel = (modo) => ({ METRICA: 'Métrica numérica', SUBMETAS: 'Sub-metas', MANUAL: 'Percentual manual' }[modo] || 'Métrica numérica');
const usuarioId = () => Number(info?.usuario?.id || 0);
const ehResponsavel = (r, o) => {
    const ids = [r?.responsavelId, o?.responsavelId].filter((v) => v !== null && v !== undefined && v !== '').map(Number);
    return usuarioId() > 0 && ids.includes(usuarioId());
};
const podeAtuarNoResultado = (r, o) => pode(info, 'okrs.manage') || (pode(info, 'okrs.update') && ehResponsavel(r, o));

async function agir(fn, ok, botao) {
    if (botao) botao.disabled = true;
    try { await fn(); mensagem(msg, ok, 'sucesso'); await renderizar(); }
    catch (e) { mensagem(msg, e.message); }
    finally { if (botao) botao.disabled = false; }
}

function renderSubMetas(r, gerir, atualizar, arquivado) {
    if (r.modoAcompanhamento !== 'SUBMETAS') return '';
    const itens = r.subMetas || [];
    const bloqueado = arquivado || r.status === 'CANCELADO';
    const podeAdicionar = atualizar && !bloqueado;
    return `<section class="okr-submetas" aria-label="Sub-metas do resultado ${esc(r.titulo)}">
      <div class="okr-submetas-cabecalho">
        <div><h4>Sub-metas</h4><small>${esc(r.subMetasConcluidas || 0)} de ${esc(r.totalSubMetas || 0)} etapas concluídas</small></div>
        ${podeAdicionar ? `<button type="button" class="botao primario" data-add-submeta="${r.id}">Adicionar etapa</button>` : ''}
      </div>
      ${itens.length ? `<ul class="okr-submetas-lista">${itens.map((sm) => `<li class="${sm.concluida ? 'concluida' : ''}">
        <label class="okr-submeta-item"><input type="checkbox" data-submeta-check="${sm.id}" ${sm.concluida ? 'checked' : ''} ${!atualizar || bloqueado ? 'disabled' : ''}/><span><strong>${esc(sm.titulo)}</strong>${sm.descricao ? `<small>${esc(sm.descricao)}</small>` : ''}</span></label>
        ${gerir && !bloqueado ? `<div class="okr-etapa-acoes"><button type="button" class="acao-mini" data-edit-submeta="${sm.id}" aria-label="Editar etapa ${esc(sm.titulo)}">Editar</button><button type="button" class="acao-mini excluir" data-del-submeta="${sm.id}" aria-label="Remover etapa ${esc(sm.titulo)}">Remover</button></div>` : ''}
      </li>`).join('')}</ul>` : '<p class="okr-vazio">Nenhuma etapa cadastrada.</p>'}
    </section>`;
}

async function renderizar() {
    if (!id) return mensagem(msg, 'Objetivo não informado.');
    const atual = ++busca;
    if (!conteudo.firstElementChild) carregando(conteudo);
    try {
        const o = await api.buscarObjetivo(id);
        if (atual !== busca) return;
        const gerir = pode(info, 'okrs.manage'), arquivado = o.status === 'ARQUIVADO';
        document.getElementById('tituloPagina').textContent = o.titulo;
        acoes.innerHTML = '<a class="botao secundario" href="/okrs.html">← OKRs</a>';
        if (o.squadId && pode(info, 'squads.view')) {
            acoes.insertAdjacentHTML('beforeend', `<a class="botao secundario" href="/squad-detalhes.html?id=${encodeURIComponent(o.squadId)}">Voltar à squad</a>`);
        }
        if (gerir && !arquivado) acoes.append(botao('Editar objetivo', 'secundario', () => formObjetivo(o)),
            botao('Arquivar', 'secundario', async () => { if (await confirmar('Arquivar este objetivo? O histórico é preservado.')) agir(() => api.arquivarObjetivo(o.id), 'Objetivo arquivado.'); }));

        const cards = (o.resultados || []).map((r) => {
            const atualizar = podeAtuarNoResultado(r, o);
            const ativo = !arquivado && r.status !== 'CANCELADO';
            const linhaMetricas = r.modoAcompanhamento === 'METRICA'
                ? `<div><small>Inicial</small><strong>${numero(r.valorInicial, 4)} ${esc(r.unidade)}</strong></div><div><small>Atual</small><strong>${numero(r.valorAtual, 4)} ${esc(r.unidade)}</strong></div><div><small>Alvo</small><strong>${numero(r.meta, 4)} ${esc(r.unidade)}</strong></div>`
                : '';
            const status = r.status === 'CANCELADO' ? selo('Cancelado', 'neutro') : r.status === 'CONCLUIDO' ? selo('Concluído', 'ok') : selo('Em andamento', 'neutro');
            return `<article class="okr-resultado-card">
              <div class="okr-resultado-topo"><div><div class="okr-resultado-titulo"><h3>${esc(r.titulo)}</h3>${status}</div>${r.descricao ? `<p>${esc(r.descricao)}</p>` : ''}</div><span class="okr-modo">${esc(modoLabel(r.modoAcompanhamento))}</span></div>
              <div class="okr-avanco okr-avanco-resultado">${barra(r.progresso, `Progresso de ${r.titulo}`)}</div>
              <div class="okr-dados-grid">${linhaMetricas}<div><small>Prazo</small><strong>${data(r.prazo)}</strong></div><div><small>Responsável</small><strong>${esc(r.responsavelNome || '—')}</strong></div></div>
              ${renderSubMetas(r, gerir, atualizar, arquivado)}
              ${ativo && !atualizar ? '<p class="okr-permissao-ajuda">Para atualizar, solicite acesso de gestor ou a permissão de progresso e a atribuição como responsável.</p>' : ''}
              <div class="okr-resultado-acoes">
                ${ativo && atualizar && r.modoAcompanhamento === 'METRICA' ? `<button type="button" class="botao primario" data-prog="${r.id}">Atualizar valor</button>` : ''}
                ${ativo && atualizar && r.modoAcompanhamento === 'MANUAL' ? `<button type="button" class="botao primario" data-percent="${r.id}">Definir percentual</button>` : ''}
                <button type="button" class="acao-mini" data-hist="${r.id}">Histórico</button>
                ${ativo && gerir ? `<button type="button" class="acao-mini" data-edit="${r.id}">Editar resultado</button><button type="button" class="acao-mini excluir" data-canc="${r.id}">Cancelar resultado</button>` : ''}
              </div>
            </article>`;
        }).join('');

        conteudo.innerHTML = `${o.descricao ? `<p class="okr-descricao">${esc(o.descricao)}</p>` : ''}<div class="detalhe-lista okr-resumo">
          <div><small>Situação</small>${o.atrasado ? selo('Atrasado', 'erro') : selo(o.status === 'ATIVO' ? 'Ativo' : o.status === 'CONCLUIDO' ? 'Concluído' : 'Arquivado', o.status === 'ATIVO' ? 'ok' : 'neutro')}</div>
          <div><small>Período</small><strong>${data(o.periodoInicio)} a ${data(o.periodoFim)}</strong></div>
          <div><small>Squad</small><strong>${esc(o.squadNome || '—')}</strong></div><div><small>Responsável</small><strong>${esc(o.responsavelNome || '—')}</strong></div>
          <div class="okr-resumo-progresso"><small>Progresso geral</small><div class="okr-avanco">${barra(o.progresso, 'Progresso do objetivo')}</div></div></div>
          <section class="okr-secao" aria-labelledby="titulo-resultados">
            <div class="okr-secao-topo"><h2 id="titulo-resultados">Resultados-chave</h2>
              ${gerir && !arquivado ? '<button type="button" class="botao primario" data-novo-resultado>Novo resultado-chave</button>' : ''}
            </div>
            ${cards ? `<div class="okr-resultados-lista">${cards}</div>` : '<p class="okr-vazio">Nenhum resultado-chave cadastrado.</p>'}
          </section>`;

        const kr = (resultId) => o.resultados.find((r) => String(r.id) === String(resultId));
        const etapa = (stageId) => o.resultados.flatMap((r) => r.subMetas || []).find((item) => String(item.id) === stageId);
        conteudo.querySelector('[data-novo-resultado]')?.addEventListener('click', (e) => formResultado(o, null, e.currentTarget));
        conteudo.querySelectorAll('[data-hist]').forEach((b) => b.onclick = () => historico(kr(b.dataset.hist)));
        conteudo.querySelectorAll('[data-prog]').forEach((b) => b.onclick = () => progresso(kr(b.dataset.prog)));
        conteudo.querySelectorAll('[data-percent]').forEach((b) => b.onclick = () => percentualManual(kr(b.dataset.percent)));
        conteudo.querySelectorAll('[data-edit]').forEach((b) => b.onclick = () => formResultado(o, kr(b.dataset.edit), b));
        conteudo.querySelectorAll('[data-canc]').forEach((b) => b.onclick = async () => { if (await confirmar('Cancelar este resultado-chave? O histórico é preservado.')) agir(() => api.cancelarResultado(b.dataset.canc), 'Resultado cancelado.', b); });
        conteudo.querySelectorAll('[data-add-submeta]').forEach((b) => b.onclick = () => formSubMeta(b.dataset.addSubmeta));
        conteudo.querySelectorAll('[data-edit-submeta]').forEach((b) => b.onclick = () => formSubMeta(null, etapa(b.dataset.editSubmeta)));
        conteudo.querySelectorAll('[data-del-submeta]').forEach((b) => b.onclick = async () => { if (await confirmar('Remover esta etapa? O percentual será recalculado.')) agir(() => api.removerSubMeta(b.dataset.delSubmeta), 'Etapa removida.', b); });
        conteudo.querySelectorAll('[data-submeta-check]').forEach((input) => input.addEventListener('change', async () => {
            const valorSelecionado = input.checked;
            const controles = conteudo.querySelectorAll('[data-submeta-check]:enabled');
            controles.forEach((controle) => { controle.disabled = true; });
            try {
                await api.atualizarConclusaoSubMeta(input.dataset.submetaCheck, valorSelecionado);
                mensagem(msg, valorSelecionado ? 'Etapa concluída.' : 'Etapa reaberta.', 'sucesso');
                await renderizar();
            } catch (e) {
                input.checked = !valorSelecionado;
                mensagem(msg, e.message);
            } finally {
                controles.forEach((controle) => { controle.disabled = false; });
                if (document.activeElement === document.body) {
                    conteudo.querySelector(`[data-submeta-check="${input.dataset.submetaCheck}"]`)?.focus({ preventScroll: true });
                }
            }
        }));
    } catch (e) {
        if (atual !== busca) return;
        mensagem(msg, e.message);
        if (!conteudo.querySelector('.okr-secao')) conteudo.innerHTML = '';
    }
}

async function formObjetivo(o) {
    const [squads, funcs] = await Promise.all([
        pode(info, 'squads.view') ? listarSquads().catch(() => []) : [],
        funcionariosParaOkrs(info).catch(() => []),
    ]);
    const opcoesSquads = [{ valor: '', rotulo: 'Nenhuma' }, ...squads.map((s) => ({ valor: s.id, rotulo: s.nome }))];
    if (o.squadId && !squads.some((s) => s.id === o.squadId)) opcoesSquads.push({ valor: o.squadId, rotulo: o.squadNome });
    const ok = await abrirFormulario({ titulo: 'Editar objetivo', campos: [
        { nome: 'titulo', rotulo: 'Título', valor: o.titulo, obrigatorio: true, maxCaracteres: 150 }, { nome: 'descricao', rotulo: 'Descrição', tipo: 'textarea', valor: o.descricao, maxCaracteres: 1000 },
        { nome: 'squadId', rotulo: 'Squad', tipo: 'select', valor: o.squadId ?? '', opcoes: opcoesSquads },
        { nome: 'responsavelId', rotulo: 'Responsável', tipo: 'select', valor: o.responsavelId ?? '', opcoes: opcoesResponsaveis(funcs, o.responsavelId, o.responsavelNome) },
        { nome: 'periodoInicio', rotulo: 'Início', tipo: 'date', valor: String(o.periodoInicio).slice(0, 10), obrigatorio: true }, { nome: 'periodoFim', rotulo: 'Fim', tipo: 'date', valor: String(o.periodoFim).slice(0, 10), obrigatorio: true }],
        salvar: (v) => api.atualizarObjetivo(o.id, { ...v, squadId: idOuNulo(v.squadId), responsavelId: idOuNulo(v.responsavelId) }) });
    if (ok) { mensagem(msg, 'Objetivo atualizado.', 'sucesso'); renderizar(); }
}

async function formResultado(o, r, botao) {
    botao.disabled = true;
    const funcs = await funcionariosParaOkrs(info).catch(() => []);
    botao.disabled = false;
    let resultado;
    const ok = await abrirFormulario({ titulo: r ? 'Editar resultado-chave' : 'Novo resultado-chave',
        textoSalvar: r ? 'Salvar' : 'Criar resultado-chave', campos: [
        { nome: 'titulo', rotulo: 'Título', valor: r?.titulo, obrigatorio: true, maxCaracteres: 150 },
        { nome: 'descricao', rotulo: 'Descrição', tipo: 'textarea', valor: r?.descricao, maxCaracteres: 1000 },
        { nome: 'modoAcompanhamento', rotulo: 'Como deseja medir esta meta?', tipo: 'select', valor: r?.modoAcompanhamento || 'METRICA', opcoes: [
            { valor: 'METRICA', rotulo: 'Métrica numérica (valor inicial e alvo)' },
            { valor: 'SUBMETAS', rotulo: 'Sub-metas (marcar etapas concluídas)' },
            { valor: 'MANUAL', rotulo: 'Percentual manual (definir de 0% a 100%)' }] },
        { nome: 'valorInicial', rotulo: 'Valor inicial', tipo: 'number', valor: r?.valorInicial ?? 0, obrigatorio: true, mostrarQuando: { campo: 'modoAcompanhamento', valor: 'METRICA' } },
        { nome: 'meta', rotulo: 'Valor-alvo', tipo: 'number', valor: r?.modoAcompanhamento === 'METRICA' ? r.meta : '', obrigatorio: true, mostrarQuando: { campo: 'modoAcompanhamento', valor: 'METRICA' } },
        ...(r?.modoAcompanhamento === 'MANUAL' ? [] : [
            { nome: 'progressoManual', rotulo: 'Percentual inicial', tipo: 'number', passo: '0.01', minimo: 0, maximo: 100,
                valor: r?.progresso ?? 0, obrigatorio: true, mostrarQuando: { campo: 'modoAcompanhamento', valor: 'MANUAL' } },
        ]),
        { nome: 'unidade', rotulo: 'Unidade de medida', valor: r?.unidade, ajuda: 'Ex.: R$, %, clientes.', maxCaracteres: 30, mostrarQuando: { campo: 'modoAcompanhamento', valor: 'METRICA' } },
        { nome: 'responsavelId', rotulo: 'Responsável', tipo: 'select', valor: r?.responsavelId ?? '', opcoes: opcoesResponsaveis(funcs, r?.responsavelId, r?.responsavelNome) },
        { nome: 'prazo', rotulo: 'Prazo', tipo: 'date', valor: String(r?.prazo || o.periodoFim).slice(0, 10), obrigatorio: true }],
        salvar: async (v) => {
            const c = { ...v, responsavelId: idOuNulo(v.responsavelId) };
            resultado = r ? await api.atualizarResultado(r.id, c) : await api.criarResultado(o.id, c);
        } });
    if (!ok) return;
    mensagem(msg, 'Resultado-chave salvo.', 'sucesso');
    await renderizar();
    if (!r) {
        const proximaAcao = conteudo.querySelector(`[data-add-submeta="${resultado.id}"], [data-percent="${resultado.id}"], [data-prog="${resultado.id}"]`);
        proximaAcao?.focus();
    }
}

async function formSubMeta(resultadoId, etapa) {
    const ok = await abrirFormulario({ titulo: etapa ? 'Editar etapa' : 'Adicionar etapa',
        textoSalvar: etapa ? 'Salvar' : 'Adicionar etapa', campos: [
        { nome: 'titulo', rotulo: 'Nome da etapa', obrigatorio: true, valor: etapa?.titulo, maxCaracteres: 150 },
        { nome: 'descricao', rotulo: 'Descrição (opcional)', tipo: 'textarea', valor: etapa?.descricao, maxCaracteres: 500 }],
        salvar: (v) => etapa ? api.atualizarSubMeta(etapa.id, v) : api.criarSubMeta(resultadoId, v) });
    if (!ok) return;
    mensagem(msg, etapa ? 'Etapa atualizada.' : 'Etapa adicionada.', 'sucesso');
    await renderizar();
    const seletor = etapa ? `[data-edit-submeta="${etapa.id}"]` : `[data-add-submeta="${resultadoId}"]`;
    conteudo.querySelector(seletor)?.focus({ preventScroll: true });
}

async function progresso(r) {
    const ok = await abrirFormulario({ titulo: `Atualizar: ${r.titulo}`, campos: [
        { nome: 'valorAtual', rotulo: `Novo valor atual (${r.unidade || 'unidade'})`, tipo: 'number', passo: 'any', valor: r.valorAtual, obrigatorio: true },
        { nome: 'comentario', rotulo: 'Comentário', tipo: 'textarea', ajuda: 'Registrado no histórico.', maxCaracteres: 500 }],
        salvar: (v) => api.atualizarProgresso(r.id, v.valorAtual, v.comentario) });
    if (ok) { mensagem(msg, 'Progresso atualizado e registrado no histórico.', 'sucesso'); renderizar(); }
}

async function percentualManual(r) {
    const ok = await abrirFormulario({ titulo: `Definir progresso: ${r.titulo}`, campos: [
        { nome: 'percentual', rotulo: 'Percentual de conclusão', tipo: 'number', passo: '0.01', minimo: 0, maximo: 100, valor: r.progresso, obrigatorio: true, ajuda: 'Informe um valor de 0 a 100.' },
        { nome: 'comentario', rotulo: 'Comentário (opcional)', tipo: 'textarea', ajuda: 'A alteração fica registrada no histórico.', maxCaracteres: 500 }],
        salvar: (v) => api.atualizarPercentual(r.id, Number(v.percentual), v.comentario) });
    if (ok) {
        mensagem(msg, 'Percentual salvo.', 'sucesso');
        await renderizar();
        conteudo.querySelector(`[data-percent="${r.id}"]`)?.focus({ preventScroll: true });
    }
}

async function historico(r) {
    try {
        const h = await api.historicoResultado(r.id);
        const modoHistorico = r.modoAcompanhamento === 'MANUAL' ? '%' : (r.unidade || 'valor');
        const dlg = document.createElement('dialog'); dlg.className = 'historico-dialog dialogo-form';
        dlg.innerHTML = `<div class="historico-topo"><h2>Histórico: ${esc(r.titulo)}</h2></div>${h.length ? `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Quando</th><th>De (${esc(modoHistorico)})</th><th>Para (${esc(modoHistorico)})</th><th>Quem</th><th>Comentário</th></tr></thead><tbody>${h.map((x) => `<tr><td>${dataHora(x.criadoEm)}</td><td>${numero(x.valorAnterior, 4)}</td><td>${numero(x.valorNovo, 4)}</td><td>${esc(x.usuarioNome || '—')}</td><td>${esc(x.comentario)}</td></tr>`).join('')}</tbody></table></div>` : '<p class="estado-vazio">Nenhuma atualização registrada.</p>'}<div class="botoes"><button class="botao secundario" type="button">Fechar</button></div>`;
        dlg.querySelector('h2').id = 'titulo-historico-okr';
        dlg.setAttribute('aria-labelledby', 'titulo-historico-okr');
        dlg.addEventListener('close', () => dlg.remove(), { once: true });
        document.body.appendChild(dlg); dlg.querySelector('button').onclick = () => dlg.close(); dlg.showModal();
    } catch (e) { mensagem(msg, e.message); }
}

(async () => {
    try {
        info = await carregarPermissoes();
        await renderizar();
    } catch (e) { mensagem(msg, e.message); }
})();
