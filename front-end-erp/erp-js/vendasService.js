/**
 * vendasService.js
 * ---------------------------------------------------------------------------
 * Regra de negócio de "Vendas". Antes de gravar a venda, é preciso garantir
 * que existe estoque suficiente para TODOS os itens (regra descrita no
 * alerta da própria tela vendas.html). Se faltar estoque em qualquer item,
 * a venda inteira é recusada — nenhum item é gravado parcialmente.
 * ---------------------------------------------------------------------------
 */

import { apiService, ApiError } from './apiService.js'; // apiService: usado no bloco "ETAPA 3" comentado abaixo
import { storageService } from './storageService.js';
import { buscarProduto, ajustarEstoque } from './produtosService.js';

const TABELA = 'vendas';

/** Lista as vendas do usuário logado (usado hoje só para contar no dashboard). */
export async function listarVendas() {
    // ===== ETAPA 3 (Spring Boot) =====
    return apiService.get('/vendas');

    // ===== ETAPA 2 (localStorage) =====
    return storageService.listarPorUsuario(TABELA);
}

/**
 * Confere, item a item, se há quantidade suficiente em estoque.
 * Lança um ApiError (status 400) descrevendo o primeiro produto que faltar.
 */
async function validarEstoqueSuficiente(itens) {
    for (const item of itens) {
        const produto = await buscarProduto(item.produtoId);
        const disponivel = produto?.quantidadeEstoque || 0;
        if (!produto || disponivel < item.quantidade) {
            const nomeProduto = produto ? produto.nome : 'selecionado';
            throw new ApiError(
                `Estoque insuficiente para o produto "${nomeProduto}" (disponível: ${disponivel}).`,
                400,
                null
            );
        }
    }
}

/**
 * Registra uma nova venda, validando estoque antes, e dá saída no estoque
 * de cada produto vendido.
 * @param {object} vendaDTO - ver dto.js -> toVendaDTO
 */
export async function criarVenda(vendaDTO) {
    // ===== ETAPA 3 (Spring Boot) =====
    // No backend, a validação de estoque e o desconto das quantidades devem
    // acontecer dentro da MESMA transação da gravação da venda, para evitar
    // vender o mesmo produto duas vezes em requisições simultâneas.
    return apiService.post('/vendas', vendaDTO);

    // ===== ETAPA 2 (localStorage) =====
    await validarEstoqueSuficiente(vendaDTO.itens);

    const vendaCriada = storageService.criar(TABELA, vendaDTO);

    for (const item of vendaDTO.itens) {
        await ajustarEstoque(item.produtoId, -item.quantidade);
    }

    return vendaCriada;
}
