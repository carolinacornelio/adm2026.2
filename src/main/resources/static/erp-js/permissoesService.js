/**
 * Permissões na INTERFACE. Servem só para esconder o que o usuário não pode usar: a segurança real
 * é aplicada pelo backend (PermissaoInterceptor). Fonte: GET /api/auth/me.
 */
import { apiService } from './apiService.js';
import { getUsuarioLogado } from './authService.js';

const CHAVE = 'permissoesUsuarioV2';
let consulta;

export function permissoesEmCache() {
    try {
        const dados = JSON.parse(sessionStorage.getItem(CHAVE) || 'null');
        const usuario = getUsuarioLogado();
        return dados?.usuario && dados.usuario.id === usuario?.id && dados.usuario.empresaId === usuario?.empresaId ? dados : null;
    } catch { return null; }
}

/** Busca (uma vez por sessão do navegador) as permissões efetivas do usuário autenticado. */
export async function carregarPermissoes(forcar = false) {
    const guardado = permissoesEmCache();
    if (guardado && !forcar) return guardado;
    consulta ??= apiService.get('/auth/me').then((dados) => {
        const resultado = { usuario: dados.usuario || null, proprietario: Boolean(dados.proprietario), permissoes: dados.permissoes || [] };
        sessionStorage.setItem(CHAVE, JSON.stringify(resultado));
        return resultado;
    }).finally(() => { consulta = undefined; });
    return consulta;
}

export function limparPermissoes() { sessionStorage.removeItem(CHAVE); }

export function pode(info, codigo) {
    return Boolean(info && (info.proprietario || info.permissoes.includes(codigo)));
}

/** Atualiza a navegação e os controles declarados com data-permissao. */
export function aplicarPermissoesNaInterface(info) {
    document.querySelectorAll('[data-permissao]:not(body)').forEach((el) => {
        el.hidden = !pode(info, el.dataset.permissao);
    });
    window.erpUi?.renderizar(info);
}

/** Páginas administrativas declaram <body data-permissao="users.view">; sem ela, volta ao dashboard. */
export function protegerPaginaPorPermissao(info) {
    const exigida = document.body?.dataset?.permissao;
    if (exigida && !pode(info, exigida)) {
        window.alert('Você não tem permissão para acessar esta página.');
        window.location.replace('/dashboard.html');
        return false;
    }
    return true;
}
