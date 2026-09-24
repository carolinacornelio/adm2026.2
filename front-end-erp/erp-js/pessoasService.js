/**
 * pessoasService.js
 * ---------------------------------------------------------------------------
 * Regra de negócio do domínio "Pessoas" (clientes/fornecedores).
 * Mesma estratégia de migração descrita em authService.js: cada função tem
 * um bloco ETAPA 2 (ativo) e um bloco ETAPA 3 (comentado, pronto para usar).
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js'; // usado nos blocos "ETAPA 3" comentados abaixo
import { storageService } from './storageService.js';

const TABELA = 'pessoas';

/** Lista todas as pessoas do usuário logado. */
export async function listarPessoas() {
    // ===== ETAPA 3 (Spring Boot) =====
    // return apiService.get('/pessoas');

    // ===== ETAPA 2 (localStorage) =====
    return storageService.listarPorUsuario(TABELA);
}

/** Busca uma pessoa pelo id. */
export async function buscarPessoa(id) {
    // ===== ETAPA 3 (Spring Boot) =====
    // encodeURIComponent(id): garante que o id nunca quebre a URL (ex: se
    // vier com "/", "?" ou espaços) nem seja interpretado como parte extra
    // do caminho da requisição.
    // return apiService.get(`/pessoas/${encodeURIComponent(id)}`);

    // ===== ETAPA 2 (localStorage) =====
    return storageService.buscarPorId(TABELA, id);
}

/** Cria uma nova pessoa. @param {object} pessoaDTO - ver dto.js -> toPessoaDTO */
export async function criarPessoa(pessoaDTO) {
    // ===== ETAPA 3 (Spring Boot) =====
    // return apiService.post('/pessoas', pessoaDTO);

    // ===== ETAPA 2 (localStorage) =====
    return storageService.criar(TABELA, pessoaDTO);
}

/** Atualiza uma pessoa existente. */
export async function atualizarPessoa(id, pessoaDTO) {
    // ===== ETAPA 3 (Spring Boot) =====
    // return apiService.put(`/pessoas/${encodeURIComponent(id)}`, pessoaDTO);

    // ===== ETAPA 2 (localStorage) =====
    return storageService.atualizar(TABELA, id, pessoaDTO);
}

/** Remove uma pessoa. */
export async function removerPessoa(id) {
    // ===== ETAPA 3 (Spring Boot) =====
    // await apiService.delete(`/pessoas/${encodeURIComponent(id)}`);
    // return true;

    // ===== ETAPA 2 (localStorage) =====
    return storageService.remover(TABELA, id);
}
