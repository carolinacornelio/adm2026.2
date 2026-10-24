/**
 * atendimentoService.js
 * ---------------------------------------------------------------------------
 * Domínio "Atendimento" (chamados/tickets de suporte técnico).
 * Segue o mesmo padrão de pessoasService.js: toda requisição passa pelo
 * apiService, que cuida da URL base e do token JWT.
 * ---------------------------------------------------------------------------
 */
import { apiService } from './apiService.js';

/** Lista os chamados do usuário logado. */
export async function listarChamados() {
    return apiService.get('/chamados');
}

/** Abre um chamado. @param {{cliente, titulo, descricao, severidade}} chamadoDTO */
export async function criarChamado(chamadoDTO) {
    return apiService.post('/chamados', chamadoDTO);
}

export async function atualizarSituacao(id, situacao) {
    return apiService.put(`/chamados/${id}/situacao`, { situacao });
}
