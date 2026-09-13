/**
 * dashboardService.js
 * ---------------------------------------------------------------------------
 * Agrega os indicadores exibidos nos "cartões" do dashboard (Pessoas,
 * Produtos, Compras, Vendas). Hoje calcula tudo a partir do localStorage;
 * na Etapa 3 essa conta passa a ser feita no backend, e o front-end só
 * exibe o resultado pronto.
 *
 * Compras e Vendas ainda não têm cadastro implementado nesta etapa do
 * projeto (ver compras.html/vendas.html), por isso os totais começam em 0 —
 * a estrutura já está pronta para quando esses módulos existirem.
 * ---------------------------------------------------------------------------
 */

import { apiService } from './apiService.js'; // usado no bloco "ETAPA 3" comentado abaixo
import { listarPessoas } from './pessoasService.js';
import { listarProdutos } from './produtosService.js';
import { listarCompras } from './comprasService.js';
import { listarVendas } from './vendasService.js';
import { fromDashboardIndicadoresDTO } from './dto.js';

export async function buscarIndicadores() {
    // ===== ETAPA 3 (Spring Boot) =====
    // const dados = await apiService.get('/dashboard/indicadores');
    // return fromDashboardIndicadoresDTO(dados);

    // ===== ETAPA 2 (localStorage) =====
    // Reaproveita os services de cada domínio em vez de ler o localStorage
    // direto — assim o dashboard não precisa saber COMO cada dado é
    // guardado, só que existe uma função "listar" para cada um.
    const [pessoas, produtos, compras, vendas] = await Promise.all([
        listarPessoas(),
        listarProdutos(),
        listarCompras(),
        listarVendas(),
    ]);

    return fromDashboardIndicadoresDTO({
        totalPessoas: pessoas.length,
        totalProdutos: produtos.length,
        totalCompras: compras.length,
        totalVendas: vendas.length,
    });
}
