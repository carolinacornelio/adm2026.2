import * as api from './funcionariosService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, mensagem, carregando, vazio, selo, abrirFormulario, confirmar } from './adminUi.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
let info, catalogo = [];
const novo = document.createElement('button'); novo.className = 'botao primario'; novo.textContent = '+ Novo perfil'; novo.hidden = true; acoes.appendChild(novo);
const opcoes = () => catalogo.map((p) => ({ valor: p.codigo, rotulo: `${p.modulo} — ${p.descricao}` }));

async function listar() {
    carregando(conteudo);
    try {
        const lista = await api.listarPerfis();
        if (!lista.length) return vazio(conteudo, 'Nenhum perfil cadastrado.');
        const gerir = pode(info, 'permissions.manage');
        conteudo.innerHTML = `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Perfil</th><th>Descrição</th><th>Tipo</th><th>Permissões</th><th>Funcionários</th>${gerir ? '<th>Ações</th>' : ''}</tr></thead><tbody>${lista.map((p) => `
          <tr><td>${esc(p.nome)} ${p.ativo ? '' : selo('Desativado', 'neutro')}</td><td>${esc(p.descricao)}</td><td>${p.sistema ? 'Inicial' : 'Personalizado'}</td>
          <td>${p.permissoes.length}</td><td>${esc(p.totalUsuarios)}</td>
          ${gerir ? `<td class="acoes-tabela">${p.chaveSistema === 'PROPRIETARIO' ? '<small>Protegido</small>' : `<button class="acao-mini" data-editar="${p.id}">Editar</button>${p.sistema ? '' : `<button class="acao-mini excluir" data-excluir="${p.id}">Excluir</button>`}`}</td>` : ''}</tr>`).join('')}</tbody></table></div>`;
        conteudo.querySelectorAll('[data-editar]').forEach((b) => b.onclick = () => formulario(lista.find((p) => p.id == b.dataset.editar)));
        conteudo.querySelectorAll('[data-excluir]').forEach((b) => b.onclick = async () => {
            if (!(await confirmar('Excluir este perfil?'))) return;
            try { await api.excluirPerfil(b.dataset.excluir); mensagem(msg, 'Perfil excluído.', 'sucesso'); listar(); } catch (e) { mensagem(msg, e.message); }
        });
    } catch (e) { mensagem(msg, e.message); conteudo.innerHTML = ''; }
}

async function formulario(p) {
    const ok = await abrirFormulario({ titulo: p ? `Editar perfil ${p.nome}` : 'Novo perfil', campos: [
        { nome: 'nome', rotulo: 'Nome', valor: p?.nome, obrigatorio: true }, { nome: 'descricao', rotulo: 'Descrição', valor: p?.descricao },
        { nome: 'permissoes', rotulo: 'Permissões concedidas', tipo: 'multicheck', opcoes: opcoes(), valor: p?.permissoes || [] }],
        salvar: (v) => (p ? api.atualizarPerfil(p.id, v) : api.criarPerfil(v)) });
    if (ok) { mensagem(msg, 'Perfil salvo.', 'sucesso'); listar(); }
}

(async () => {
    info = await carregarPermissoes();
    if (pode(info, 'permissions.manage')) { novo.hidden = false; novo.onclick = () => formulario(null); try { catalogo = await api.listarPermissoes(); } catch (e) { mensagem(msg, e.message); } }
    listar();
})();
