/**
 * app.js
 * ---------------------------------------------------------------------------
 * Script "global", carregado em TODAS as páginas internas do ERP (dashboard,
 * pessoas, produtos, compras, vendas, estoque). Responsável por três coisas:
 *
 *   1) Proteger as páginas internas: se não houver usuário logado, redireciona
 *      para o login (isso é o que o passo de teste do README descreve:
 *      "Faça logout e tente acessar uma tela do ERP: deverá voltar ao login").
 *   2) Exibir o nome do usuário logado no cabeçalho.
 *   3) Ligar o botão "Sair" (data-logout) ao authService.logout().
 *
 * As páginas de autenticação (login.html, cadastro.html) NÃO usam este guard,
 * pois nelas ainda não existe usuário logado.
 * ---------------------------------------------------------------------------
 */

import { estaAutenticado, getUsuarioLogado, logout } from './authService.js';

const PAGINAS_PUBLICAS = ['login.html', 'cadastro.html', 'index.html', ''];

function nomeDaPaginaAtual() {
    return window.location.pathname.split('/').pop();
}

function protegerPaginaInterna() {
    if (PAGINAS_PUBLICAS.includes(nomeDaPaginaAtual())) return;

    if (!estaAutenticado()) {
        window.location.href = 'login.html';
    }
}

function exibirUsuarioLogado() {
    const usuario = getUsuarioLogado();
    const elementoUsuario = document.querySelector('.usuario');
    if (usuario && elementoUsuario) {
        elementoUsuario.textContent = `👤 ${usuario.nome} ▾`;
    }
}

function configurarBotaoLogout() {
    const botaoLogout = document.querySelector('[data-logout]');
    if (botaoLogout) {
        botaoLogout.addEventListener('click', () => logout());
    }
}

// SEGURANÇA: chamamos a proteção de página AGORA, fora de qualquer listener,
// como código de nível superior do módulo. Scripts com type="module" (como
// este) já executam depois que o HTML foi interpretado, mas ANTES do evento
// "DOMContentLoaded" ser disparado. Isso significa que, se o usuário não
// estiver autenticado, o redirecionamento para login.html começa a ser
// processado antes mesmo dos outros scripts da página (dashboard.js,
// pessoas.js...) começarem a buscar dados — reduzindo ao mínimo a janela de
// tempo em que uma tela protegida fica "montada" sem usuário logado.
protegerPaginaInterna();

document.addEventListener('DOMContentLoaded', () => {
    exibirUsuarioLogado();
    configurarBotaoLogout();
});
