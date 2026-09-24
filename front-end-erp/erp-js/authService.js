/**
 * authService.js
 * ---------------------------------------------------------------------------
 * Regra de negócio de autenticação (cadastro, login, logout, sessão).
 *
 * Cada função abaixo tem dois blocos:
 *   - ETAPA 2 (ativo hoje): usa `authStorage` (localStorage) como simulação.
 *   - ETAPA 3 (comentado): chamada real ao backend Spring Boot via `apiService`.
 *
 * Quando o Spring estiver pronto, basta comentar o bloco "ETAPA 2" e
 * descomentar o bloco "ETAPA 3" — nenhum outro arquivo do projeto (auth.js,
 * app.js) precisa mudar, pois todos usam apenas as funções exportadas aqui.
 * ---------------------------------------------------------------------------
 */

import { apiService, ApiError } from './apiService.js'; // apiService: usado nos blocos "ETAPA 3" comentados abaixo
import { authStorage } from './storageService.js';

/**
 * Cria um novo usuário/empresa.
 * @param {object} usuarioCadastroDTO - ver dto.js -> toUsuarioCadastroDTO
 */
export async function cadastrar(usuarioCadastroDTO) {
    // ===== ETAPA 3 (Spring Boot) =====
    // const usuarioCriado = await apiService.post('/auth/cadastro', usuarioCadastroDTO);
    // authStorage... // o token/dados de sessão viriam prontos na resposta
    // return usuarioCriado;

    // ===== ETAPA 2 (localStorage - simulação atual) =====
    const jaExiste = authStorage.buscarPorEmail(usuarioCadastroDTO.email);
    if (jaExiste) {
        // Simula o mesmo erro (409) que o Spring devolveria neste caso.
        throw new ApiError('Já existe uma conta cadastrada com este e-mail.', 409, null);
    }
    const novoUsuario = authStorage.criarUsuario(usuarioCadastroDTO);
    authStorage.iniciarSessao(novoUsuario);
    return novoUsuario;
}

/**
 * Autentica um usuário.
 * @param {object} usuarioLoginDTO - ver dto.js -> toUsuarioLoginDTO
 */
export async function login(usuarioLoginDTO) {
    // ===== ETAPA 3 (Spring Boot) =====
    // const resposta = await apiService.post('/auth/login', usuarioLoginDTO);
    // sessionStorage.setItem('authToken', resposta.token);
    // return resposta.usuario;

    // ===== ETAPA 2 (localStorage - simulação atual) =====
    const usuario = authStorage.buscarPorEmail(usuarioLoginDTO.email);
    if (!usuario || usuario.senha !== usuarioLoginDTO.senha) {
        // Simula o erro 401 que o Spring devolveria para credenciais inválidas.
        throw new ApiError('E-mail ou senha inválidos.', 401, null);
    }
    authStorage.iniciarSessao(usuario);
    return usuario;
}

/** Encerra a sessão do usuário atual e o redireciona para o login. */
export function logout() {
    // ===== ETAPA 3 (Spring Boot) =====
    // sessionStorage.removeItem('authToken');

    // ===== ETAPA 2 (localStorage - simulação atual) =====
    authStorage.encerrarSessao();

    window.location.href = 'login.html';
}

/** Devolve os dados do usuário logado (ou null). */
export function getUsuarioLogado() {
    return authStorage.getUsuarioLogado();
}

/** Verifica se existe uma sessão ativa. */
export function estaAutenticado() {
    return authStorage.estaAutenticado();
}
