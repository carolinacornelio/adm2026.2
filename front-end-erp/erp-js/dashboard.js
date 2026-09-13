/**
 * dashboard.js
 * ---------------------------------------------------------------------------
 * Controller do dashboard.html. Preenche os 4 "cartões" de indicador
 * (Pessoas, Produtos, Compras, Vendas) na mesma ordem em que aparecem no
 * HTML — não foi necessário adicionar ids porque os cartões já seguem uma
 * ordem fixa dentro de <div class="cartoes">.
 * ---------------------------------------------------------------------------
 */

import { buscarIndicadores } from './dashboardService.js';
import { estaAutenticado } from './authService.js';

async function carregarIndicadores() {
    const cartoes = document.querySelectorAll('.cartoes .cartao .indicador');
    if (cartoes.length === 0) return;

    try {
        const indicadores = await buscarIndicadores();
        const valores = [
            indicadores.totalPessoas,
            indicadores.totalProdutos,
            indicadores.totalCompras,
            indicadores.totalVendas,
        ];

        cartoes.forEach((elemento, indice) => {
            elemento.textContent = valores[indice] ?? 0;
        });
    } catch (erro) {
        console.error('Não foi possível carregar os indicadores do dashboard:', erro);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;
    carregarIndicadores();
});
