import * as api from './funcionariosService.js';
import { listarSquads } from './squadsService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, data, mensagem, carregando, vazio, selo, abrirFormulario, confirmar } from './adminUi.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
let info, perfis = [], squads = [];

acoes.innerHTML = `<input id="busca" type="search" placeholder="Pesquisar por nome ou e-mail..."/>
  <select id="filtroStatus" aria-label="Situação"><option value="">Todos</option><option value="ativos">Ativos</option><option value="inativos">Inativos</option></select>`;
const novo = document.createElement('button'); novo.className = 'botao primario'; novo.textContent = '+ Novo funcionário'; novo.hidden = true;
acoes.appendChild(novo);

async function opcoes() {
    try { perfis = (await api.listarPerfis()).filter((p) => p.ativo && p.chaveSistema !== 'PROPRIETARIO'); } catch { perfis = []; }
    try { squads = await listarSquads(); } catch { squads = []; }
}
const optPerfis = () => perfis.map((p) => ({ valor: p.id, rotulo: p.nome }));
const optSquads = () => squads.map((s) => ({ valor: s.id, rotulo: s.nome }));

async function listar() {
    carregando(conteudo);
    try {
        const lista = await api.listarFuncionarios({ status: document.getElementById('filtroStatus').value, busca: document.getElementById('busca').value.trim() });
        if (!lista.length) return vazio(conteudo, 'Nenhum funcionário encontrado.');
        const gerir = pode(info, 'users.manage');
        conteudo.innerHTML = `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Nome</th><th>E-mail</th><th>Perfis</th><th>Squads</th><th>Situação</th>${gerir ? '<th>Ações</th>' : ''}</tr></thead><tbody>${lista.map((f) => `
          <tr><td>${esc(f.nome)} ${f.proprietario ? selo('Proprietário', 'ok') : ''}</td><td>${esc(f.email)}</td>
          <td>${esc(f.perfis.map((p) => p.nome).join(', ') || '—')}</td><td>${esc(f.squads.map((s) => s.nome).join(', ') || '—')}</td>
          <td>${selo(f.ativo ? 'Ativo' : 'Inativo', f.ativo ? 'ok' : 'neutro')}</td>
          ${gerir ? `<td class="acoes-tabela"><button class="acao-mini" data-editar="${f.id}">Editar</button>
            <button class="acao-mini" data-senha="${f.id}">Redefinir senha</button>
            ${f.proprietario ? '' : `<button class="acao-mini ${f.ativo ? 'excluir' : ''}" data-status="${f.id}" data-ativo="${f.ativo}">${f.ativo ? 'Desativar' : 'Ativar'}</button>`}</td>` : ''}</tr>`).join('')}</tbody></table></div>`;
        conteudo.querySelectorAll('[data-editar]').forEach((b) => b.onclick = () => editar(lista.find((f) => f.id == b.dataset.editar)));
        conteudo.querySelectorAll('[data-senha]').forEach((b) => b.onclick = () => redefinir(lista.find((f) => f.id == b.dataset.senha)));
        conteudo.querySelectorAll('[data-status]').forEach((b) => b.onclick = () => alternar(lista.find((f) => f.id == b.dataset.status)));
    } catch (e) { mensagem(msg, e.message); conteudo.innerHTML = ''; }
}

async function criar() {
    const ok = await abrirFormulario({ titulo: 'Novo funcionário', textoSalvar: 'Criar', campos: [
        { nome: 'nome', rotulo: 'Nome', obrigatorio: true }, { nome: 'email', rotulo: 'E-mail', tipo: 'email', obrigatorio: true },
        { nome: 'senha', rotulo: 'Senha inicial', tipo: 'password', obrigatorio: true, ajuda: 'Mínimo de 6 caracteres. No primeiro acesso a pessoa define as perguntas de segurança.' },
        { nome: 'perfis', rotulo: 'Perfis de acesso', tipo: 'multicheck', opcoes: optPerfis() },
        { nome: 'squads', rotulo: 'Squads', tipo: 'multicheck', opcoes: optSquads() }],
        salvar: (v) => api.criarFuncionario({ ...v, perfis: v.perfis.map(Number), squads: v.squads.map(Number) }) });
    if (ok) { mensagem(msg, 'Funcionário criado.', 'sucesso'); listar(); }
}

async function editar(f) {
    const campos = [{ nome: 'nome', rotulo: 'Nome', valor: f.nome, obrigatorio: true }, { nome: 'email', rotulo: 'E-mail', tipo: 'email', valor: f.email, obrigatorio: true }];
    if (!f.proprietario) {
        campos.push({ nome: 'perfis', rotulo: 'Perfis de acesso', tipo: 'multicheck', opcoes: optPerfis(), valor: f.perfis.map((p) => p.id) });
        campos.push({ nome: 'squads', rotulo: 'Squads', tipo: 'multicheck', opcoes: optSquads(), valor: f.squads.map((s) => s.id) });
    }
    const ok = await abrirFormulario({ titulo: `Editar ${f.nome}`, campos, salvar: (v) => {
        const corpo = { nome: v.nome, email: v.email };
        if (!f.proprietario) { corpo.perfis = v.perfis.map(Number); corpo.squads = v.squads.map(Number); }
        return api.atualizarFuncionario(f.id, corpo);
    } });
    if (ok) { mensagem(msg, 'Funcionário atualizado.', 'sucesso'); listar(); }
}

async function redefinir(f) {
    let resposta = null;
    const ok = await abrirFormulario({ titulo: `Redefinir senha de ${f.nome}`, textoSalvar: 'Redefinir', campos: [
        { nome: 'senha', rotulo: 'Nova senha', tipo: 'password', ajuda: 'Deixe em branco para gerar uma senha temporária. Os acessos abertos serão encerrados.' }],
        salvar: async (v) => { resposta = await api.redefinirSenhaFuncionario(f.id, v.senha || null); } });
    if (ok) mensagem(msg, resposta?.senhaTemporaria ? `Senha temporária de ${f.nome}: ${resposta.senhaTemporaria} — anote agora, ela não será exibida novamente.` : 'Senha redefinida.', 'sucesso');
}

async function alternar(f) {
    const ativar = !f.ativo;
    if (!(await confirmar(ativar ? `Reativar ${f.nome}?` : `Desativar ${f.nome}? O acesso será encerrado imediatamente.`))) return;
    try { await api.alterarStatusFuncionario(f.id, ativar); mensagem(msg, ativar ? 'Funcionário reativado.' : 'Funcionário desativado.', 'sucesso'); listar(); }
    catch (e) { mensagem(msg, e.message); }
}

(async () => {
    info = await carregarPermissoes();
    if (pode(info, 'users.manage')) { novo.hidden = false; novo.onclick = criar; await opcoes(); }
    document.getElementById('filtroStatus').onchange = listar;
    let t; document.getElementById('busca').oninput = () => { clearTimeout(t); t = setTimeout(listar, 300); };
    listar();
})();
