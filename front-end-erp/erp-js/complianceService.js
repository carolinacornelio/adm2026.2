/**
 * complianceService.js
 * ---------------------------------------------------------------------------
 * SERVIÇOS DE COMPLIANCE & SEGURANÇA (LEI ANTICORRUPÇÃO Nº 12.846/2013)
 * ---------------------------------------------------------------------------
 */
import { apiService } from './apiService.js';

export const complianceService = {
    /**
     * ETAPA 2.3: Relatório de Transações de Risco (BI)
     */
    async listarAlertas() {
        return apiService.get('/compliance/alertas');
    },

    /**
     * ETAPA 1.1: Auditoria de Permissões e Matriz SoD
     */
    async auditarSoD() {
        return apiService.get('/compliance/sod-auditoria');
    },

    /**
     * Lista contratos de fornecedores cadastrados
     */
    async listarContratos() {
        return apiService.get('/compliance/contratos');
    },

    /**
     * Cadastra um novo contrato de fornecedor
     */
    async criarContrato(dadosContrato) {
        return apiService.post('/compliance/contratos', dadosContrato);
    },

    /**
     * Homologa formalmente um contrato por compliance
     */
    async homologarContrato(contratoId) {
        return apiService.put(`/compliance/contratos/${contratoId}/homologar`, {});
    },

    /**
     * ETAPA 1.3: Fila de aprovação de dados bancários (Princípio dos 4 Olhos)
     */
    async listarAlteracoesBancariasPendentes() {
        return apiService.get('/compliance/alteracoes-pendentes');
    },

    /**
     * Aprova alteração bancária de fornecedor (segundo aprovador)
     */
    async aprovarAlteracaoBancaria(id) {
        return apiService.post(`/compliance/alteracoes-pendentes/${id}/aprovar`, {});
    },

    /**
     * Rejeita alteração bancária suspeita
     */
    async rejeitarAlteracaoBancaria(id) {
        return apiService.post(`/compliance/alteracoes-pendentes/${id}/rejeitar`, {});
    },

    /**
     * ETAPA 2.2: Aprovação de alçada de compra
     */
    async aprovarCompra(compraId) {
        return apiService.post(`/compras/${compraId}/aprovar`, {});
    },

    /**
     * ETAPA 1.1: Liquidação de compra respeitando a Regra de Ouro SoD
     */
    async liquidarPagamento(compraId) {
        return apiService.post(`/compras/${compraId}/pagar`, {});
    }
};
