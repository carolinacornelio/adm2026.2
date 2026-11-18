import { buscarEmpresa, atualizarEmpresa } from './empresaService.js';
import { alterarMinhaSenha } from './funcionariosService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, data, mensagem, carregando, abrirFormulario, selo } from './adminUi.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');

async function renderizar() {
    carregando(conteudo);
    try {
        const [e, info] = await Promise.all([buscarEmpresa(), carregarPermissoes()]);
        conteudo.innerHTML = `<div class="detalhe-lista">
            <div><small>Nome fantasia</small><strong>${esc(e.nomeFantasia)}</strong></div>
            <div><small>Documento</small><strong>${esc(e.documento || '—')}</strong></div>
            <div><small>Plano</small><strong>${esc(e.plano)}</strong></div>
            <div><small>Situação da conta</small>${selo(e.status === 'ATIVA' ? 'Ativa' : e.status, e.status === 'ATIVA' ? 'ok' : 'erro')}</div>
            <div><small>Criada em</small><strong>${data(e.criadoEm)}</strong></div>
            <div><small>Proprietário</small><strong>${esc(e.proprietario?.nome || '—')}</strong><br><small>${esc(e.proprietario?.email || '')}</small></div>
            <div><small>Funcionários ativos</small><strong>${esc(e.funcionariosAtivos)}</strong></div>
            <div><small>Funcionários inativos</small><strong>${esc(e.funcionariosInativos)}</strong></div></div>`;
        acoes.innerHTML = '';
        if (pode(info, 'company.update')) {
            const b = document.createElement('button'); b.className = 'botao primario'; b.textContent = 'Editar dados';
            b.onclick = async () => {
                const ok = await abrirFormulario({ titulo: 'Editar empresa', campos: [
                    { nome: 'nomeFantasia', rotulo: 'Nome fantasia', valor: e.nomeFantasia, obrigatorio: true },
                    { nome: 'documento', rotulo: 'Documento empresarial', valor: e.documento || '', ajuda: 'Opcional.' }],
                    salvar: (v) => atualizarEmpresa(v) });
                if (ok) { mensagem(msg, 'Dados da empresa atualizados.', 'sucesso'); renderizar(); }
            };
            acoes.appendChild(b);
        }
        const s = document.createElement('button'); s.className = 'botao secundario'; s.textContent = 'Alterar minha senha';
        s.onclick = async () => {
            const ok = await abrirFormulario({ titulo: 'Alterar minha senha', campos: [
                { nome: 'atual', rotulo: 'Senha atual', tipo: 'password', obrigatorio: true },
                { nome: 'nova', rotulo: 'Nova senha', tipo: 'password', obrigatorio: true, ajuda: 'Mínimo de 6 caracteres. Seus outros acessos serão encerrados.' }],
                salvar: (v) => alterarMinhaSenha(v.atual, v.nova) });
            if (ok) mensagem(msg, 'Senha alterada com sucesso.', 'sucesso');
        };
        acoes.appendChild(s);
    } catch (erro) { mensagem(msg, erro.message); conteudo.innerHTML = ''; }
}
renderizar();
