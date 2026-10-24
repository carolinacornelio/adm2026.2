<<<<<<< Updated upstream
/**
 * estoque.js
 * ---------------------------------------------------------------------------
 * Controller da tela estoque.html: lista a situação de todos os produtos
 * (estoque atual x estoque mínimo) e permite pesquisar por nome.
 *
 * Regra de atualização: a lista é recarregada ao abrir a página, ao voltar
 * para a aba, a cada INTERVALO_MS enquanto a aba estiver visível e quando o
 * usuário clica em "Atualizar". A pesquisa filtra a lista já carregada.
 * ---------------------------------------------------------------------------
 */
=======
import { apiService } from './apiService.js';
import { estaAutenticado } from './authService.js';
import { escapeHtml as e } from './uiService.js';
import { classeStatusEstoque, classeStatusValidade, textoStatusValidade, badgeValidade } from './uiService.js';
import { fromProdutoDTO } from './dto.js';
import { aoMovimentar } from './estoqueSync.js';
>>>>>>> Stashed changes

import { listarSituacaoEstoque } from './estoqueService.js';
import { estaAutenticado } from './authService.js';
import { renderizarTabela, classeStatusEstoque, escapeHtml } from './uiService.js';

const INTERVALO_MS = 30000;

let produtos = [];
let carregando = false;

function criarLinhaEstoque(produto) {
    return `
        <tr>
            <td>${escapeHtml(produto.nome)}</td>
            <td>${produto.quantidadeEstoque}</td>
            <td>${produto.estoqueMinimo}</td>
            <td><span class="status ${classeStatusEstoque(produto.situacao)}">${produto.situacao}</span></td>
        </tr>
    `;
}

function desenhar(tbody, termoBusca = '') {
    const termo = termoBusca.trim().toLowerCase();
    const filtrados = termo
        ? produtos.filter((p) => p.nome.toLowerCase().includes(termo))
        : produtos;

    renderizarTabela(tbody, filtrados, criarLinhaEstoque, 'Nenhum produto cadastrado ainda.');
}

async function atualizar(tbody, campoPesquisa, rotulo) {
    if (carregando) return;
    carregando = true;
    try {
<<<<<<< Updated upstream
        produtos = await listarSituacaoEstoque();
        desenhar(tbody, campoPesquisa?.value || '');
        if (rotulo) rotulo.textContent = `Atualizado às ${new Date().toLocaleTimeString('pt-BR')}`;
    } catch (erro) {
        console.error(erro);
        if (!produtos.length) {
            tbody.innerHTML = `<tr><td colspan="4">Não foi possível carregar o estoque.</td></tr>`;
        } else if (rotulo) {
            rotulo.textContent = 'Falha ao atualizar; exibindo os últimos dados carregados.';
=======
        [produtos,lotes] = await Promise.all([apiService.get('/estoque'),apiService.get(`/inventario/lotes?dias=${encodeURIComponent($('#diasAlerta').value)}`)]);
        desenhar();
        $('#estadoInventario').textContent = conectado ? 'Atualização automática conectada' : 'Dados consultados';
        $('#exportarInventario').disabled=false; $('#imprimirInventario').disabled=false;
    } catch(erro) {
        $('#estadoInventario').textContent = erro.message;
        $('#listaLotes').innerHTML='<tr><td colspan="6" class="estado-vazio">Não foi possível consultar o inventário.</td></tr>';
    } finally { carregando=false; if(repetir) {repetir=false;void carregar();} }
}
async function acompanhar() {
    let primeiraConexao = true;
    while(!controller.signal.aborted) {
        // Ao reconectar, reconsulta: eventos emitidos durante a queda não são reenviados.
        if(!primeiraConexao) void carregar();
        primeiraConexao = false;
        try {
            await apiService.events('/pos/eventos',{signal:controller.signal,onEvent:()=>{conectado=true;void carregar();}});
        } catch(erro) {
            if(controller.signal.aborted) return;
            conectado=false; $('#estadoInventario').textContent='Reconectando atualizações automáticas...';
            if(erro.status===401) {window.location.href='login.html';return;}
>>>>>>> Stashed changes
        }
    } finally {
        carregando = false;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;

    const tbody = document.getElementById('listaEstoque');
    if (!tbody) return;

    const campoPesquisa = document.getElementById('pesquisaEstoque');
    const botaoAtualizar = document.getElementById('atualizarEstoque');
    const rotulo = document.getElementById('ultimaAtualizacao');
    const recarregar = () => atualizar(tbody, campoPesquisa, rotulo);

    recarregar();

    campoPesquisa?.addEventListener('input', () => desenhar(tbody, campoPesquisa.value));
    botaoAtualizar?.addEventListener('click', recarregar);

    // Voltar para a aba do estoque mostra o dado novo na hora (o cenário do teste de duas abas).
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') recarregar();
    });
<<<<<<< Updated upstream

    // Atualização periódica enquanto a aba estiver visível.
    setInterval(() => {
        if (document.visibilityState === 'visible') recarregar();
    }, INTERVALO_MS);
=======
    $('#listaLotes').addEventListener('click',ev=>{
        const b=ev.target.closest('[data-regularizar]');if(!b)return;
        const lote=lotes.find(l=>String(l.id)===b.dataset.regularizar);
        $('#loteId').value=lote.id;$('#codigoLote').value=lote.lote;$('#dataLote').value='';$('#erroLote').textContent='';$('#regularizarLote').showModal();
    });
    $('#formRegularizar').addEventListener('submit',async ev=>{
        ev.preventDefault();const b=ev.submitter;b.disabled=true;
        try {await apiService.put(`/inventario/lotes/${$('#loteId').value}/validade`,{lote:$('#codigoLote').value,validade:$('#dataLote').value});$('#regularizarLote').close();await carregar();}catch(erro){$('#erroLote').textContent=erro.message;}finally{b.disabled=false;}
    });
    $('#cancelarLote').addEventListener('click',()=>$('#regularizarLote').close());
    $('#exportarInventario').addEventListener('click',exportar);
    $('#imprimirInventario').addEventListener('click',()=>{document.body.dataset.impressao='inventario';window.print();});
    window.addEventListener('afterprint',()=>delete document.body.dataset.impressao);
    window.addEventListener('pagehide',()=>controller.abort(),{once:true});
    window.addEventListener('pageshow',ev=>{if(ev.persisted)window.location.reload();});
    document.addEventListener('visibilitychange',()=>{if(!document.hidden)void carregar();});
    // Camadas extras além do stream /pos/eventos:
    // 1) aviso direto de compras/vendas registradas em outra aba (não depende do backend);
    aoMovimentar(() => void carregar());
    // 2) rede de segurança: reconsulta a cada 15 s (mesmo padrão de produtos.js).
    window.setInterval(() => { if(!document.hidden) void carregar(); }, 15000);
    void carregar();void acompanhar();
>>>>>>> Stashed changes
});
