/**
 * Permissões na INTERFACE. Servem só para esconder o que o usuário não pode usar: a segurança real
 * é aplicada pelo backend (PermissaoInterceptor). Fonte: GET /api/auth/me.
 */
import { apiService } from './apiService.js';

const CHAVE = 'permissoesUsuarioV2';

/** Rota de menu -> permissão de visualização (apenas conveniência visual). */
const PERMISSAO_POR_PAGINA = {
    'pessoas-listagem.html': 'people.view', 'pessoas-cadastro.html': 'people.manage',
    'produtos-listagem.html': 'products.view', 'produtos-cadastro.html': 'products.create', 'categorias.html': 'products.view',
    'compras.html': 'purchases.view', 'vendas.html': 'sales.view', 'historico-vendas.html': 'sales.view',
    'estoque.html': 'stock.view', 'pos.html': 'pos.use', 'ia.html': 'ai.use', 'atendimento.html': 'support.use',
    'financeiro.html': 'finance.view', 'funcionarios.html': 'users.view', 'perfis-acesso.html': 'users.view',
    'empresas.html': 'company.view', 'squads.html': 'squads.view', 'squad-detalhes.html': 'squads.view',
    'okrs.html': 'okrs.view', 'objetivo-detalhes.html': 'okrs.view',
};

export function permissoesEmCache() {
    try { return JSON.parse(sessionStorage.getItem(CHAVE) || 'null'); } catch { return null; }
}

/** Busca (uma vez por sessão do navegador) as permissões efetivas do usuário autenticado. */
export async function carregarPermissoes(forcar = false) {
    const guardado = permissoesEmCache();
    if (guardado && !forcar) return guardado;
    const dados = await apiService.get('/auth/me');
    const resultado = { usuario: dados.usuario || null, proprietario: Boolean(dados.proprietario), permissoes: dados.permissoes || [] };
    sessionStorage.setItem(CHAVE, JSON.stringify(resultado));
    return resultado;
}

export function limparPermissoes() { sessionStorage.removeItem(CHAVE); }

export function pode(info, codigo) {
    return Boolean(info && (info.proprietario || info.permissoes.includes(codigo)));
}

/** Esconde links sem permissão, injeta a pasta "Administração" e honra data-permissao="x.y" nos elementos. */
export function aplicarPermissoesNaInterface(info) {
    document.querySelectorAll('.menu a[href]').forEach((a) => {
        const rota = (a.getAttribute('href') || '').split(/[?#]/, 1)[0].split('/').pop();
        const exigida = PERMISSAO_POR_PAGINA[rota];
        if (exigida && !pode(info, exigida)) a.hidden = true;
    });
    document.querySelectorAll('[data-permissao]').forEach((el) => { if (!pode(info, el.dataset.permissao)) el.hidden = true; });
    injetarMenuAdministracao(info);
}

function injetarMenuAdministracao(info) {
    const menu = document.querySelector('.menu');
    if (!menu || menu.querySelector('[data-pasta="administracao"]')) return;
    const itens = [
        ['empresas.html', 'Empresa', 'company.view'], ['funcionarios.html', 'Funcionários', 'users.view'],
        ['perfis-acesso.html', 'Perfis de acesso', 'users.view'], ['squads.html', 'Squads', 'squads.view'],
        ['okrs.html', 'OKRs', 'okrs.view'], ['financeiro.html', 'Financeiro', 'finance.view'],
    ].filter(([, , p]) => pode(info, p));
    if (!itens.length) return;
    const atual = window.location.pathname.split('/').pop();
    const pasta = document.createElement('details');
    pasta.className = 'menu-pasta'; pasta.dataset.pasta = 'administracao';
    pasta.open = itens.some(([h]) => h === atual || (atual === 'squad-detalhes.html' && h === 'squads.html') || (atual === 'objetivo-detalhes.html' && h === 'okrs.html'));
    pasta.innerHTML = '<summary><span class="menu-pasta-icone" aria-hidden="true"></span><span>Administração</span><span class="menu-pasta-seta" aria-hidden="true"></span></summary><div class="menu-pasta-itens"></div>';
    const lista = pasta.querySelector('.menu-pasta-itens');
    itens.forEach(([href, texto]) => {
        const a = document.createElement('a'); a.href = href.startsWith('/') ? href : `/${href}`; a.textContent = texto;
        if (href === atual) a.className = 'ativo';
        lista.appendChild(a);
    });
    menu.appendChild(pasta);
}

/** Páginas administrativas declaram <body data-permissao="users.view">; sem ela, volta ao dashboard. */
export function protegerPaginaPorPermissao(info) {
    const exigida = document.body?.dataset?.permissao;
    if (exigida && !pode(info, exigida)) {
        window.alert('Você não tem permissão para acessar esta página.');
        window.location.href = '/dashboard.html';
        return false;
    }
    return true;
}
