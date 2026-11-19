import * as api from './okrsService.js';
import { listarSquads } from './squadsService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, data, numero, mensagem, carregando, vazio, selo, barra } from './adminUi.js?v=okrs-4';
import { funcionariosParaOkrs, novoObjetivo } from './objetivoForm.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
let info, squads = [], funcs = [];
let busca = 0;
conteudo.innerHTML = `<div id="resumo"></div>
 <div class="grade-filtros">
  <div class="campo"><label for="fStatus">Situação</label><select id="fStatus"><option value="">Em aberto</option><option value="ATIVO">Ativos</option><option value="CONCLUIDO">Concluídos</option><option value="ARQUIVADO">Arquivados</option></select></div>
  <div class="campo"><label for="fSquad">Squad</label><select id="fSquad"><option value="">Todas</option></select></div>
  <div class="campo"><label for="fResp">Responsável</label><select id="fResp"><option value="">Todos</option></select></div>
  <div class="campo"><label for="fDe">Período de</label><input id="fDe" type="date"/></div>
  <div class="campo"><label for="fAte">até</label><input id="fAte" type="date"/></div></div>
 <div id="lista"></div>`;
const novo = document.createElement('button'); novo.className = 'botao primario'; novo.textContent = '+ Novo objetivo'; novo.hidden = true; acoes.appendChild(novo);
const $ = (i) => document.getElementById(i);

async function resumo() {
    try {
        const r = await api.resumoOkrs(), g = r.geral;
        $('resumo').innerHTML = `<div class="cartoes">
          <div class="cartao"><small>Objetivos ativos</small><div class="indicador">${esc(g.objetivosAtivos)}</div></div>
          <div class="cartao"><small>Resultados em andamento</small><div class="indicador">${esc(g.resultadosEmAndamento)}</div></div>
          <div class="cartao"><small>Progresso médio</small><div class="indicador">${numero(g.progressoMedio)}%</div></div>
          <div class="cartao"><small>Concluídos</small><div class="indicador">${esc(g.objetivosConcluidos)}</div></div>
          <div class="cartao"><small>Atrasados</small><div class="indicador">${esc(g.objetivosAtrasados)}</div></div></div>
          ${r.porSquad.length ? `<h2>Progresso por Squad</h2><div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Squad</th><th>Objetivos</th><th>Progresso</th></tr></thead><tbody>${r.porSquad.map((s) => `<tr><td>${esc(s.nome)}</td><td>${esc(s.objetivos)}</td><td>${barra(s.progresso)}</td></tr>`).join('')}</tbody></table></div>` : ''}
          ${r.porResponsavel.length ? `<h2>Progresso por responsável</h2><div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Responsável</th><th>Resultados</th><th>Progresso</th></tr></thead><tbody>${r.porResponsavel.map((s) => `<tr><td>${esc(s.nome)}</td><td>${esc(s.resultados)}</td><td>${barra(s.progresso)}</td></tr>`).join('')}</tbody></table></div>` : ''}`;
    } catch (e) { $('resumo').innerHTML = ''; mensagem(msg, e.message); }
}

async function listar() {
    const atual = ++busca;
    carregando($('lista'));
    try {
        const lista = await api.listarObjetivos({ status: $('fStatus').value, squadId: $('fSquad').value, responsavelId: $('fResp').value, de: $('fDe').value, ate: $('fAte').value });
        if (atual !== busca) return;
        if (!lista.length) return vazio($('lista'), 'Nenhum objetivo encontrado para os filtros.');
        $('lista').innerHTML = `<h2>Objetivos</h2><div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Objetivo</th><th>Squad</th><th>Responsável</th><th>Período</th><th>Progresso</th><th>Situação</th></tr></thead><tbody>${lista.map((o) => `
          <tr><td><a href="/objetivo-detalhes.html?id=${encodeURIComponent(o.id)}">${esc(o.titulo)}</a><br><small>${esc(o.totalResultados)} resultado(s)-chave</small></td>
          <td>${esc(o.squadNome || '—')}</td><td>${esc(o.responsavelNome || '—')}</td><td>${data(o.periodoInicio)} a ${data(o.periodoFim)}</td><td>${barra(o.progresso)}</td>
          <td>${o.atrasado ? selo('Atrasado', 'erro') : selo(o.status === 'ATIVO' ? 'Ativo' : o.status === 'CONCLUIDO' ? 'Concluído' : 'Arquivado', o.status === 'ATIVO' ? 'ok' : 'neutro')}</td></tr>`).join('')}</tbody></table></div>`;
    } catch (e) {
        if (atual !== busca) return;
        mensagem(msg, e.message);
        $('lista').innerHTML = '';
    }
}

async function criar() {
    const objetivo = await novoObjetivo({ squads, funcionarios: funcs });
    if (objetivo) location.assign(`/objetivo-detalhes.html?id=${encodeURIComponent(objetivo.id)}`);
}

(async () => {
    info = await carregarPermissoes();
    [squads, funcs] = await Promise.all([
        pode(info, 'squads.view') ? listarSquads().catch(() => []) : [],
        funcionariosParaOkrs(info).catch(() => []),
    ]);
    $('fSquad').innerHTML += squads.map((s) => `<option value="${esc(s.id)}">${esc(s.nome)}</option>`).join('');
    $('fResp').innerHTML += funcs.map((f) => `<option value="${esc(f.id)}">${esc(f.nome)}</option>`).join('');
    ['fStatus', 'fSquad', 'fResp', 'fDe', 'fAte'].forEach((i) => $(i).addEventListener('change', listar));
    if (pode(info, 'okrs.manage')) { novo.hidden = false; novo.onclick = criar; }
    resumo(); listar();
})();
