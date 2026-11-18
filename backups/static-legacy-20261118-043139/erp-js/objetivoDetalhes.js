import * as api from './okrsService.js';
import { listarSquads } from './squadsService.js';
import { listarFuncionarios } from './funcionariosService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, data, dataHora, numero, mensagem, carregando, selo, barra, abrirFormulario, confirmar } from './adminUi.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
const id = new URLSearchParams(location.search).get('id');
let info, funcs = [], squads = [];
const botao = (t, c, fn) => { const b = document.createElement('button'); b.className = `botao ${c}`; b.textContent = t; b.onclick = fn; return b; };
const respOpts = () => [{ valor: '', rotulo: 'Nenhum' }, ...funcs.map((f) => ({ valor: f.id, rotulo: f.nome }))];
const idOuNulo = (v) => (v ? Number(v) : null);
async function agir(fn, ok) { try { await fn(); mensagem(msg, ok, 'sucesso'); renderizar(); } catch (e) { mensagem(msg, e.message); } }

async function renderizar() {
    if (!id) return mensagem(msg, 'Objetivo não informado.');
    carregando(conteudo);
    try {
        const o = await api.buscarObjetivo(id);
        const gerir = pode(info, 'okrs.manage'), atualizar = pode(info, 'okrs.update'), arquivado = o.status === 'ARQUIVADO';
        document.getElementById('tituloPagina').textContent = o.titulo;
        acoes.innerHTML = '<a class="botao secundario" href="okrs.html">← OKRs</a>';
        if (gerir && !arquivado) acoes.append(botao('+ Resultado-chave', 'primario', () => formResultado(o, null)), botao('Editar', 'secundario', () => formObjetivo(o)),
            botao('Arquivar', 'secundario', async () => { if (await confirmar('Arquivar este objetivo? O histórico é preservado.')) agir(() => api.arquivarObjetivo(o.id), 'Objetivo arquivado.'); }));
        conteudo.innerHTML = `<p>${esc(o.descricao)}</p><div class="detalhe-lista">
          <div><small>Situação</small>${o.atrasado ? selo('Atrasado', 'erro') : selo(o.status === 'ATIVO' ? 'Ativo' : o.status === 'CONCLUIDO' ? 'Concluído' : 'Arquivado', o.status === 'ATIVO' ? 'ok' : 'neutro')}</div>
          <div><small>Período</small><strong>${data(o.periodoInicio)} a ${data(o.periodoFim)}</strong></div>
          <div><small>Squad</small><strong>${esc(o.squadNome || '—')}</strong></div><div><small>Responsável</small><strong>${esc(o.responsavelNome || '—')}</strong></div>
          <div><small>Progresso geral</small>${barra(o.progresso)}</div></div>
          <h2>Resultados-chave</h2>${o.resultados.length ? `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Resultado</th><th>Inicial</th><th>Atual</th><th>Meta</th><th>Prazo</th><th>Responsável</th><th>Progresso</th><th>Ações</th></tr></thead><tbody>${o.resultados.map((r) => `
          <tr><td>${esc(r.titulo)}<br><small>${esc(r.descricao)}</small> ${r.status === 'CANCELADO' ? selo('Cancelado', 'neutro') : r.status === 'CONCLUIDO' ? selo('Concluído', 'ok') : ''}</td>
          <td>${numero(r.valorInicial, 4)} ${esc(r.unidade)}</td><td><strong>${numero(r.valorAtual, 4)}</strong> ${esc(r.unidade)}</td><td>${numero(r.meta, 4)} ${esc(r.unidade)}</td>
          <td>${data(r.prazo)}</td><td>${esc(r.responsavelNome || '—')}</td><td>${barra(r.progresso)}</td>
          <td class="acoes-tabela"><button class="acao-mini" data-hist="${r.id}">Histórico</button>
          ${!arquivado && r.status !== 'CANCELADO' && atualizar ? `<button class="acao-mini" data-prog="${r.id}">Atualizar</button>` : ''}
          ${!arquivado && r.status !== 'CANCELADO' && gerir ? `<button class="acao-mini" data-edit="${r.id}">Editar</button><button class="acao-mini excluir" data-canc="${r.id}">Cancelar</button>` : ''}</td></tr>`).join('')}</tbody></table></div>` : '<p class="estado-vazio">Nenhum resultado-chave cadastrado.</p>'}`;
        const kr = (b) => o.resultados.find((r) => r.id == b);
        conteudo.querySelectorAll('[data-hist]').forEach((b) => b.onclick = () => historico(kr(b.dataset.hist)));
        conteudo.querySelectorAll('[data-prog]').forEach((b) => b.onclick = () => progresso(kr(b.dataset.prog)));
        conteudo.querySelectorAll('[data-edit]').forEach((b) => b.onclick = () => formResultado(o, kr(b.dataset.edit)));
        conteudo.querySelectorAll('[data-canc]').forEach((b) => b.onclick = async () => { if (await confirmar('Cancelar este resultado-chave? O histórico é preservado.')) agir(() => api.cancelarResultado(b.dataset.canc), 'Resultado cancelado.'); });
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
        { nome: 'titulo', rotulo: 'Título', valor: r?.titulo, obrigatorio: true }, { nome: 'descricao', rotulo: 'Descrição', tipo: 'textarea', valor: r?.descricao },
        { nome: 'valorInicial', rotulo: 'Valor inicial', tipo: 'number', valor: r?.valorInicial ?? 0, obrigatorio: true },
        { nome: 'meta', rotulo: 'Meta', tipo: 'number', valor: r?.meta, obrigatorio: true, ajuda: 'Pode ser menor que o inicial (meta de redução), mas nunca igual.' },
        { nome: 'unidade', rotulo: 'Unidade de medida', valor: r?.unidade, ajuda: 'Ex.: R$, %, clientes.' },
        { nome: 'responsavelId', rotulo: 'Responsável', tipo: 'select', valor: r?.responsavelId ?? '', opcoes: respOpts() },
        { nome: 'prazo', rotulo: 'Prazo', tipo: 'date', valor: r ? String(r.prazo).slice(0, 10) : '', obrigatorio: true }],
        salvar: (v) => { const c = { ...v, responsavelId: idOuNulo(v.responsavelId) }; return r ? api.atualizarResultado(r.id, c) : api.criarResultado(o.id, c); } });
    if (ok) { mensagem(msg, 'Resultado-chave salvo.', 'sucesso'); renderizar(); }
}

async function progresso(r) {
    const ok = await abrirFormulario({ titulo: `Atualizar: ${r.titulo}`, campos: [
        { nome: 'valorAtual', rotulo: `Novo valor atual (${r.unidade || 'unidade'})`, tipo: 'number', valor: r.valorAtual, obrigatorio: true },
        { nome: 'comentario', rotulo: 'Comentário', tipo: 'textarea', ajuda: 'Registrado no histórico.' }],
        salvar: (v) => api.atualizarProgresso(r.id, v.valorAtual, v.comentario) });
    if (ok) { mensagem(msg, 'Progresso atualizado e registrado no histórico.', 'sucesso'); renderizar(); }
}

async function historico(r) {
    try {
        const h = await api.historicoResultado(r.id);
        const dlg = document.createElement('dialog'); dlg.className = 'historico-dialog dialogo-form';
        dlg.innerHTML = `<div class="historico-topo"><h2>Histórico: ${esc(r.titulo)}</h2></div>${h.length ? `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Quando</th><th>De</th><th>Para</th><th>Quem</th><th>Comentário</th></tr></thead><tbody>${h.map((x) => `<tr><td>${dataHora(x.criadoEm)}</td><td>${numero(x.valorAnterior, 4)}</td><td>${numero(x.valorNovo, 4)}</td><td>${esc(x.usuarioNome || '—')}</td><td>${esc(x.comentario)}</td></tr>`).join('')}</tbody></table></div>` : '<p class="estado-vazio">Nenhuma atualização registrada.</p>'}<div class="botoes"><button class="botao secundario" type="button">Fechar</button></div>`;
        document.body.appendChild(dlg); dlg.querySelector('button').onclick = () => { dlg.close(); dlg.remove(); }; dlg.showModal();
    } catch (e) { mensagem(msg, e.message); }
}

(async () => {
    info = await carregarPermissoes();
    try { squads = await listarSquads(); } catch { squads = []; }
    try { funcs = await listarFuncionarios({ status: 'ativos' }); } catch { funcs = []; }
    renderizar();
})();
