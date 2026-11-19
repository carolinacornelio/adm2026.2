import { estaAutenticado, logout } from './authService.js';
import { aplicarControlePlanos, temAcesso, nomePlano } from './planService.js';
import { carregarPermissoes, aplicarPermissoesNaInterface, protegerPaginaPorPermissao } from './permissoesService.js';

const PAGINAS_PUBLICAS = new Set(['login.html', 'cadastro.html', 'index.html', '']);

async function iniciar() {
    const pagina = location.pathname.split('/').pop();
    if (PAGINAS_PUBLICAS.has(pagina)) return;
    if (!estaAutenticado()) {
        location.replace('/login.html');
        return;
    }

    const minimo = document.body.dataset.planoMinimo;
    if (minimo && !temAcesso(minimo)) {
        window.alert(`Esta página está disponível no ${nomePlano(minimo)}.`);
        location.replace('/dashboard.html');
        return;
    }
    aplicarControlePlanos();
    const sair = document.querySelector('[data-logout]');
    sair?.addEventListener('click', () => { sair.disabled = true; void logout(); });
    try {
        const info = await carregarPermissoes();
        if (!protegerPaginaPorPermissao(info)) return;
        aplicarPermissoesNaInterface(info);
        document.documentElement.dataset.uiPermissoes = 'conhecidas';
    } catch (erro) {
        if (erro.status === 401) {
            location.replace('/login.html');
            return;
        }
        if (document.documentElement.dataset.uiPermissoes !== 'pendentes') return;

        const aviso = document.createElement('p');
        aviso.className = 'aviso-navegacao';
        aviso.setAttribute('role', 'alert');
        aviso.textContent = 'Não foi possível carregar os acessos. ';
        const tentar = document.createElement('button');
        tentar.type = 'button';
        tentar.className = 'botao secundario';
        tentar.textContent = 'Tentar novamente';
        tentar.addEventListener('click', () => location.reload());
        aviso.append(tentar);
        document.querySelector('.topo')?.after(aviso);
    }
}

void iniciar();
