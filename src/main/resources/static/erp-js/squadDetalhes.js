import * as api from './squadsService.js';
import { listarObjetivos } from './okrsService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, data, mensagem, carregando, selo, barra, abrirFormulario, confirmar } from './adminUi.js?v=okrs-4';
import { funcionariosParaOkrs, novoObjetivo } from './objetivoForm.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
const id = new URLSearchParams(location.search).get('id');
let info;

function apresentarObjetivos(objetivos) {
    if (!objetivos.length) return '<p class="okr-vazio">Nenhum objetivo vinculado a esta squad.</p>';
    return `<div class="okr-objetivos-grade">${objetivos.map((objetivo) => {
        const url = `/objetivo-detalhes.html?id=${encodeURIComponent(objetivo.id)}`;
        const status = objetivo.atrasado ? selo('Atrasado', 'erro')
            : objetivo.status === 'CONCLUIDO' ? selo('Concluído', 'ok') : selo('Ativo');
        return `<article class="okr-objetivo-card">
          <div class="okr-objetivo-topo"><h3><a href="${url}">${esc(objetivo.titulo)}</a></h3>${status}</div>
          <p>Até ${data(objetivo.periodoFim)} · ${esc(objetivo.totalResultados)} resultado(s) · ${esc(objetivo.totalSubMetas)} etapa(s)</p>
          <div class="okr-avanco">${barra(objetivo.progresso, `Progresso de ${objetivo.titulo}`)}</div>
        </article>`;
    }).join('')}</div>`;
}

async function renderizar() {
    if (!id) { mensagem(msg, 'Squad não informada.'); return; }
    carregando(conteudo);
    try {
        const podeVerOkrs = pode(info, 'okrs.view');
        const [s, objetivos] = await Promise.all([
            api.buscarSquad(id),
            podeVerOkrs ? listarObjetivos({ squadId: id }).then(
                (lista) => apresentarObjetivos(lista),
                (erro) => `<p class="okr-vazio" role="alert">Falha ao carregar objetivos. ${esc(erro.message)} <button type="button" class="acao-mini" data-recarregar-okrs>Tentar novamente</button></p>`,
            ) : '',
        ]);
        const gerir = pode(info, 'squads.manage');
        const podeCriar = podeVerOkrs && pode(info, 'okrs.manage');
        document.getElementById('tituloPagina').textContent = s.nome;
        acoes.innerHTML = '<a class="botao secundario" href="/squads.html">← Squads</a>';
        if (gerir) {
            const add = botao('Adicionar integrantes', 'secundario', () => adicionar(s)), ed = botao('Editar squad', 'secundario', () => editar(s));
            acoes.append(add, ed);
        }
        conteudo.innerHTML = `${s.descricao ? `<p class="okr-descricao">${esc(s.descricao)}</p>` : ''}<div class="detalhe-lista okr-resumo">
            <div><small>Situação</small>${selo(s.status === 'ATIVA' ? 'Ativa' : 'Inativa', s.status === 'ATIVA' ? 'ok' : 'neutro')}</div>
            <div><small>Líder</small><strong>${esc(s.liderNome || 'Sem líder')}</strong></div>
            <div><small>Integrantes ativos</small><strong>${esc(s.totalIntegrantes)}</strong></div><div><small>Criada em</small><strong>${data(s.criadoEm)}</strong></div></div>
            ${podeVerOkrs ? `<section class="okr-secao" aria-labelledby="titulo-objetivos">
              <div class="okr-secao-topo"><h2 id="titulo-objetivos">Objetivos da squad</h2>
                ${podeCriar ? '<button class="botao primario" type="button" data-novo-objetivo>Novo objetivo</button>' : ''}
              </div>${objetivos}</section>` : ''}
            <section class="okr-secao" aria-labelledby="titulo-integrantes"><h2 id="titulo-integrantes">Integrantes</h2>${s.integrantes.length ? `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Nome</th><th>E-mail</th><th>Papel</th>${gerir ? '<th>Ações</th>' : ''}</tr></thead><tbody>${s.integrantes.map((i) => `
            <tr><td>${esc(i.nome)} ${i.ativo ? '' : selo('Inativo', 'neutro')}</td><td>${esc(i.email)}</td><td>${i.lider ? selo('Líder', 'ok') : 'Integrante'}</td>
            ${gerir ? `<td class="acoes-tabela">${i.lider ? '' : `<button class="acao-mini" data-lider="${i.id}">Definir líder</button>`}<button class="acao-mini excluir" data-remover="${i.id}">Remover</button></td>` : ''}</tr>`).join('')}</tbody></table></div>` : '<p class="okr-vazio">Nenhum integrante.</p>'}</section>`;
        conteudo.querySelector('[data-recarregar-okrs]')?.addEventListener('click', renderizar);
        conteudo.querySelector('[data-novo-objetivo]')?.addEventListener('click', async (e) => {
            const botaoCriar = e.currentTarget;
            botaoCriar.disabled = true;
            try {
                const funcionarios = await funcionariosParaOkrs(info).catch(() => []);
                const objetivo = await novoObjetivo({ squad: s, funcionarios });
                if (objetivo) location.assign(`/objetivo-detalhes.html?id=${encodeURIComponent(objetivo.id)}`);
            } finally { botaoCriar.disabled = false; }
        });
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
