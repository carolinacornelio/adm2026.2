import * as api from './squadsService.js';
import { listarFuncionarios } from './funcionariosService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, mensagem, carregando, vazio, selo, abrirFormulario } from './adminUi.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
let info;
acoes.innerHTML = '<select id="filtroStatus" aria-label="Situação"><option value="">Todas</option><option value="ATIVA">Ativas</option><option value="INATIVA">Inativas</option></select>';
const novo = document.createElement('button'); novo.className = 'botao primario'; novo.textContent = '+ Nova Squad'; novo.hidden = true; acoes.appendChild(novo);

async function listar() {
    carregando(conteudo);
    try {
        const lista = await api.listarSquads(document.getElementById('filtroStatus').value);
        if (!lista.length) return vazio(conteudo, 'Nenhuma Squad encontrada.');
        conteudo.innerHTML = `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Squad</th><th>Líder</th><th>Integrantes</th><th>Situação</th><th>Ações</th></tr></thead><tbody>${lista.map((s) => `
          <tr><td><strong>${esc(s.nome)}</strong><br><small>${esc(s.descricao)}</small></td><td>${esc(s.liderNome || '—')}</td><td>${esc(s.totalIntegrantes)}</td>
          <td>${selo(s.status === 'ATIVA' ? 'Ativa' : 'Inativa', s.status === 'ATIVA' ? 'ok' : 'neutro')}</td>
          <td class="acoes-tabela"><a class="acao-mini" href="/squad-detalhes.html?id=${encodeURIComponent(s.id)}">Detalhes</a></td></tr>`).join('')}</tbody></table></div>`;
    } catch (e) { mensagem(msg, e.message); conteudo.innerHTML = ''; }
}

async function criar() {
    let funcs = [];
    try { funcs = (await listarFuncionarios({ status: 'ativos' })).map((f) => ({ valor: f.id, rotulo: f.nome })); } catch { /* sem users.view: cria sem líder */ }
    const ok = await abrirFormulario({ titulo: 'Nova Squad', textoSalvar: 'Criar', campos: [
        { nome: 'nome', rotulo: 'Nome', obrigatorio: true }, { nome: 'descricao', rotulo: 'Descrição', tipo: 'textarea' },
        { nome: 'liderId', rotulo: 'Líder', tipo: 'select', opcoes: [{ valor: '', rotulo: 'Sem líder' }, ...funcs] },
        { nome: 'integrantes', rotulo: 'Integrantes', tipo: 'multicheck', opcoes: funcs }],
        salvar: (v) => api.criarSquad({ nome: v.nome, descricao: v.descricao, liderId: v.liderId ? Number(v.liderId) : null, integrantes: v.integrantes.map(Number) }) });
    if (ok) { mensagem(msg, 'Squad criada.', 'sucesso'); listar(); }
}

(async () => {
    info = await carregarPermissoes();
    if (pode(info, 'squads.manage')) { novo.hidden = false; novo.onclick = criar; }
    document.getElementById('filtroStatus').onchange = listar;
    listar();
})();
