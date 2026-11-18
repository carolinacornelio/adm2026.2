import * as api from './squadsService.js';
import { listarObjetivos } from './okrsService.js';
import { carregarPermissoes, pode } from './permissoesService.js?v=20261118-submetas-v3';
import { esc, data, mensagem, carregando, vazio, selo, barra, abrirFormulario, confirmar } from './adminUi.js?v=20261118-submetas-v3';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
const id = new URLSearchParams(location.search).get('id');
let info;

async function renderizar() {
    if (!id) { mensagem(msg, 'Squad não informada.'); return; }
    carregando(conteudo);
    try {
        const s = await api.buscarSquad(id);
        const gerir = pode(info, 'squads.manage');
        document.getElementById('tituloPagina').textContent = s.nome;
        acoes.innerHTML = '<a class="botao secundario" href="/squads.html">← Squads</a>';
        if (gerir) {
            const add = botao('+ Integrantes', 'primario', () => adicionar(s)), ed = botao('Editar', 'secundario', () => editar(s));
            acoes.append(add, ed);
        }
        let okrs = '';
        if (pode(info, 'okrs.view')) {
            try {
                const obj = await listarObjetivos({ squadId: id });
                okrs = `<h2>Objetivos e sub-metas da Squad</h2><p class="ajuda-campo">Abra um objetivo para cadastrar etapas, marcar as concluídas e acompanhar o percentual atualizado. O progresso da Squad é calculado a partir dos resultados-chave.</p>${obj.length ? `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Objetivo</th><th>Resultados / sub-metas</th><th>Período</th><th>Progresso</th><th>Ações</th></tr></thead><tbody>${obj.map((o) => `<tr><td><a href="/objetivo-detalhes.html?id=${encodeURIComponent(o.id)}">${esc(o.titulo)}</a></td><td>${esc(o.totalResultados || 0)} resultado(s)-chave<br><small>${esc(o.totalSubMetas || 0)} etapa(s) cadastradas</small></td><td>${data(o.periodoInicio)} a ${data(o.periodoFim)}</td><td>${barra(o.progresso)}</td><td><a class="acao-mini" href="/objetivo-detalhes.html?id=${encodeURIComponent(o.id)}">Gerenciar OKRs e etapas</a></td></tr>`).join('')}</tbody></table></div>` : '<p class="estado-vazio">Nenhum objetivo vinculado. Cadastre um objetivo na área de OKRs para começar.</p>'}`;
            } catch { /* sem permissão ou falha: omite a seção */ }
        }
        conteudo.innerHTML = `<p>${esc(s.descricao)}</p><div class="detalhe-lista">
            <div><small>Situação</small>${selo(s.status === 'ATIVA' ? 'Ativa' : 'Inativa', s.status === 'ATIVA' ? 'ok' : 'neutro')}</div>
            <div><small>Líder</small><strong>${esc(s.liderNome || 'Sem líder')}</strong></div>
            <div><small>Integrantes ativos</small><strong>${esc(s.totalIntegrantes)}</strong></div><div><small>Criada em</small><strong>${data(s.criadoEm)}</strong></div></div>
            <h2>Integrantes</h2>${s.integrantes.length ? `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Nome</th><th>E-mail</th><th>Papel</th>${gerir ? '<th>Ações</th>' : ''}</tr></thead><tbody>${s.integrantes.map((i) => `
            <tr><td>${esc(i.nome)} ${i.ativo ? '' : selo('Inativo', 'neutro')}</td><td>${esc(i.email)}</td><td>${i.lider ? selo('Líder', 'ok') : 'Integrante'}</td>
            ${gerir ? `<td class="acoes-tabela">${i.lider ? '' : `<button class="acao-mini" data-lider="${i.id}">Definir líder</button>`}<button class="acao-mini excluir" data-remover="${i.id}">Remover</button></td>` : ''}</tr>`).join('')}</tbody></table></div>` : '<p class="estado-vazio">Nenhum integrante.</p>'}${okrs}`;
        conteudo.querySelectorAll('[data-lider]').forEach((b) => b.onclick = () => agir(() => api.definirLider(id, Number(b.dataset.lider)), 'Líder definido.'));
        conteudo.querySelectorAll('[data-remover]').forEach((b) => b.onclick = async () => { if (await confirmar('Remover este integrante da Squad?')) agir(() => api.removerIntegrante(id, b.dataset.remover), 'Integrante removido.'); });
    } catch (e) { mensagem(msg, e.message); conteudo.innerHTML = ''; }
}
function botao(t, c, fn) { const b = document.createElement('button'); b.className = `botao ${c}`; b.textContent = t; b.onclick = fn; return b; }
async function agir(fn, ok) { try { await fn(); mensagem(msg, ok, 'sucesso'); renderizar(); } catch (e) { mensagem(msg, e.message); } }

async function adicionar(s) {
    let cand = [];
    try { cand = (await api.candidatosSquad(s.id)).map((c) => ({ valor: c.id, rotulo: c.nome })); } catch (e) { return mensagem(msg, e.message); }
    if (!cand.length) return mensagem(msg, 'Todos os funcionários ativos já integram esta Squad.', 'sucesso');
    const ok = await abrirFormulario({ titulo: 'Adicionar integrantes', textoSalvar: 'Adicionar', campos: [{ nome: 'usuarios', rotulo: 'Funcionários', tipo: 'multicheck', opcoes: cand }],
        salvar: (v) => api.adicionarIntegrantes(s.id, v.usuarios.map(Number)) });
    if (ok) { mensagem(msg, 'Integrantes adicionados.', 'sucesso'); renderizar(); }
}
async function editar(s) {
    const ok = await abrirFormulario({ titulo: 'Editar Squad', campos: [
        { nome: 'nome', rotulo: 'Nome', valor: s.nome, obrigatorio: true }, { nome: 'descricao', rotulo: 'Descrição', tipo: 'textarea', valor: s.descricao },
        { nome: 'status', rotulo: 'Situação', tipo: 'select', valor: s.status, opcoes: [{ valor: 'ATIVA', rotulo: 'Ativa' }, { valor: 'INATIVA', rotulo: 'Inativa' }] }],
        salvar: (v) => api.atualizarSquad(s.id, v) });
    if (ok) { mensagem(msg, 'Squad atualizada.', 'sucesso'); renderizar(); }
}
(async () => { info = await carregarPermissoes(); renderizar(); })();
