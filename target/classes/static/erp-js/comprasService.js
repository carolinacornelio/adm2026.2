/**
 * comprasService.js
 * ---------------------------------------------------------------------------
 * Regra de negócio de "Compras". Uma compra dá ENTRADA no estoque de cada
 * produto listado nela — por isso, ao criar uma compra, este service também
 * chama `ajustarEstoque` (em produtosService.js) para cada item.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js'; // usado no bloco "ETAPA 3" comentado abaixo
import { storageService } from './storageService.js';
import { ajustarEstoque } from './produtosService.js';

const TABELA = 'compras';

/** Lista as compras do usuário logado (usado hoje só para contar no dashboard). */
export async function listarCompras() {
    // ===== ETAPA 3 (Spring Boot) =====
    return apiService.get('/compras');

    // ===== ETAPA 2 (localStorage) =====
    return storageService.listarPorUsuario(TABELA);
}

/**
 * Registra uma nova compra e dá entrada no estoque de cada produto.
 * @param {object} compraDTO - ver dto.js -> toCompraDTO
 */
export async function criarCompra(compraDTO) {
    // ===== ETAPA 3 (Spring Boot) =====
    // No backend, gravar a compra e dar entrada no estoque devem acontecer
    // dentro da MESMA transação (@Transactional), para nunca gravar uma
    // compra sem atualizar o estoque (ou vice-versa).
    return apiService.post('/compras', compraDTO);

    // ===== ETAPA 2 (localStorage) =====
    const compraCriada = storageService.criar(TABELA, compraDTO);

    for (const item of compraDTO.itens) {
        await ajustarEstoque(item.produtoId, item.quantidade);
    }

    return compraCriada;
}
