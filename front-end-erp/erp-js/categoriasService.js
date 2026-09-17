/**
 * categoriasService.js
 * ---------------------------------------------------------------------------
 * Serviço de categorias de produtos.
 *
 * ETAPA ATUAL: localStorage para a demonstração do front-end.
 * ETAPA SPRING: trocar listarCategorias() pela chamada indicada ao endpoint
 * GET /api/categorias.
 *
 * No banco do projeto a tabela categorias já existe e produtos possui
 * categoria_id, então o contrato abaixo está alinhado ao Script.sql.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js';
import { storageService } from './storageService.js';

const TABELA = 'categorias';

const CATEGORIAS_PADRAO = [
    { id: 'CELULAR', nome: 'Celular' },
    { id: 'COMPUTADOR', nome: 'Computador' },
    { id: 'NOTEBOOK', nome: 'Notebook' },
    { id: 'MONITOR', nome: 'Monitor' },
    { id: 'PERIFERICO', nome: 'Periférico' },
    { id: 'ACESSORIO', nome: 'Acessório' },
    { id: 'ELETRONICO', nome: 'Eletrônico' },
    { id: 'OUTROS', nome: 'Outros' },
];

function garantirCategoriasLocais() {
    const existentes = storageService.listarPorUsuario(TABELA);
    if (existentes.length > 0) return existentes;

    CATEGORIAS_PADRAO.forEach((categoria) => {
        storageService.criar(TABELA, categoria);
    });

    return storageService.listarPorUsuario(TABELA);
}

export async function listarCategorias() {
    // ===== ETAPA SPRING BOOT =====
    // return apiService.get('/categorias');

    // ===== ETAPA ATUAL =====
    return garantirCategoriasLocais();
}

export function categoriasPadrao() {
    return CATEGORIAS_PADRAO.map((categoria) => ({ ...categoria }));
}
