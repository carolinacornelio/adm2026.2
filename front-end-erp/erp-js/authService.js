/**
 * authService.js
 * ---------------------------------------------------------------------------
 * Regra de negócio de autenticação (cadastro, login, logout, sessão).
 *
 * O fluxo atual chama a API Spring Boot via `apiService`.
 * O antigo `authStorage` foi mantido apenas como referência histórica.
 *
 * O controller da tela continua desacoplado: ele usa apenas as funções
 * exportadas aqui e não conhece detalhes de HTTP ou banco de dados.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js';

/**
 * Cria um novo usuário/empresa.
 * @param {object} usuarioCadastroDTO - ver dto.js -> toUsuarioCadastroDTO
 */
export async function cadastrar(usuarioCadastroDTO) {
    // ATUAL: o servidor cria empresa/usuário, aplica BCrypt e devolve a sessão.
    const resposta = await apiService.post('/auth/cadastro', usuarioCadastroDTO);
    salvarSessao(resposta);
    return resposta.usuario;

    // ANTIGO: authStorage.criarUsuario(...) gravava a senha no localStorage.
}

/**
 * Autentica um usuário.
 * @param {object} usuarioLoginDTO - ver dto.js -> toUsuarioLoginDTO
 */
export async function login(usuarioLoginDTO) {
    // A senha inicia um desafio; a sessão só será criada após o segundo fator.
    return apiService.post('/auth/login', usuarioLoginDTO);

    // ANTIGO: comparava a senha diretamente no localStorage.
}

/** Confirma as perguntas de segurança e guarda a sessão liberada pelo backend. */
export async function verificarSegundoFator(dados) {
    const resposta = await apiService.post('/auth/segundo-fator', dados);
    salvarSessao(resposta);
    return resposta.usuario;
}

/** Encerra a sessão do usuário atual e o redireciona para o login. */
export function logout() {
    sessionStorage.removeItem('authToken');
    sessionStorage.removeItem('usuario');

    window.location.href = 'login.html';
}

/** Devolve os dados do usuário logado (ou null). */
export function getUsuarioLogado() {
    const valor = sessionStorage.getItem('usuario');
    return valor ? JSON.parse(valor) : null;
}

/** Verifica se existe uma sessão ativa. */
export function estaAutenticado() {
    return Boolean(sessionStorage.getItem('authToken'));
}

/** Guarda o token devolvido pelo backend; o tenant vem associado ao usuário. */
function salvarSessao(resposta) {
    sessionStorage.setItem('authToken', resposta.token);
    sessionStorage.setItem('usuario', JSON.stringify(resposta.usuario));
}
