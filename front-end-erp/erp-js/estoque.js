import { apiService } from './apiService.js';
import { estaAutenticado } from './authService.js';
import { escapeHtml as e } from './uiService.js';
import { classeStatusEstoque, classeStatusValidade, textoStatusValidade, badgeValidade } from './uiService.js';
import { fromProdutoDTO } from './dto.js';
import { formatarData, formatarDataHora } from './timeUtils.js';
import { iniciarSincronizacao, avisarMovimentacao } from './estoqueSync.js';

const $ = s => document.querySelector(s);
let produtos = [], lotes = [], filtrados = [], carregando = false, repetir = false;
let conectado = false;
let falhaConsulta = false;
const data = value => formatarData(value, 'Não informada');
const estadoSincronizacao = () => conectado ? 'Atualização automática conectada' : 'Consulta automática a cada 15 s; tentando conexão em tempo real';
function desenhar() {
    const termo = $('#pesquisaEstoque').value.trim().toLowerCase();
    produtos = produtos.map(p => ({ ...p, situacao: fromProdutoDTO(p).situacao }));
    $('#listaEstoque').innerHTML = produtos.filter(p => p.nome.toLowerCase().includes(termo)).map(p => `<tr><td>${e(p.nome)}</td><td>${e(p.quantidadeTotal)}</td><td><span class="status ${classeStatusEstoque(p.situacao)}">${e(p.quantidadeEstoque)} · ${e(p.situacao)}</span></td><td>${e(p.estoqueMinimo)}</td><td>${badgeValidade(p)}<button class="acao-mini" data-controle="${e(p.id)}">${p.controlaValidade ? 'Obrigatória' : 'Opcional'} — alterar</button></td></tr>`).join('') || '<tr><td colspan="5" class="estado-vazio">Nenhum produto encontrado.</td></tr>';
    const busca = $('#buscaLote').value.trim().toLowerCase();
    const situacao = $('#situacaoLote').value, de = $('#validadeDe').value, ate = $('#validadeAte').value;
    filtrados = lotes.filter(l => (!busca || `${l.nome} ${l.codigoProduto} ${l.lote}`.toLowerCase().includes(busca)) && (!situacao || l.situacao===situacao) && (!de || (l.validade && String(l.validade).slice(0,10)>=de)) && (!ate || (l.validade && String(l.validade).slice(0,10)<=ate)));
    $('#listaLotes').innerHTML = filtrados.map(l => `<tr><td>${e(l.nome)}<small> (${e(l.codigoProduto)})</small></td><td>${e(l.lote)}${l.legado ? ' (legado)' : ''}</td><td>${e(data(l.validade))}</td><td>${e(l.quantidade)}</td><td><span class="status ${classeStatusValidade(l.nivelValidade)}">${e(textoStatusValidade(l.situacao,l.nivelValidade))}</span><small class="status-detalhe">${l.diasParaVencer == null ? (l.vendavel ? 'Validade não exigida' : 'Venda bloqueada') : l.diasParaVencer < 0 ? 'Venda bloqueada' : l.diasParaVencer === 0 ? 'Vence hoje' : `Vence em ${e(l.diasParaVencer)} dia(s)`}</small></td><td class="inventario-acoes">${!l.validade ? `<button class="acao-mini" data-regularizar="${e(l.id)}">Informar validade</button>` : '—'}</td></tr>`).join('') || '<tr><td colspan="6" class="estado-vazio">Nenhum lote com saldo para esses filtros.</td></tr>';
    const qtd = s => lotes.filter(l => l.situacao===s).reduce((n,l)=>n+Number(l.quantidade),0);
    $('#alertasInventario').textContent = `Alertas do inventário: ${qtd('Vencido')} unidades vencidas; ${qtd('A vencer')} a vencer em até ${$('#diasAlerta').value} dias; ${qtd('Validade pendente')} com validade obrigatória pendente.`;
    $('#periodoRelatorio').textContent = `${filtrados.length} lotes com saldo · ${situacao || 'Todas as situações'} · validade: ${de ? data(de) : 'sem início'} a ${ate ? data(ate) : 'sem fim'} · atualizado em ${formatarDataHora(new Date())}`;
}
async function carregar() {
    if(carregando) { repetir=true; return; }
    carregando=true;
    $('#exportarInventario').disabled=true; $('#imprimirInventario').disabled=true;
    try {
        [produtos,lotes] = await Promise.all([apiService.get('/estoque'),apiService.get(`/inventario/lotes?dias=${encodeURIComponent($('#diasAlerta').value)}`)]);
        desenhar();
        falhaConsulta = false;
        $('#estadoInventario').textContent = estadoSincronizacao();
        $('#exportarInventario').disabled=false; $('#imprimirInventario').disabled=false;
    } catch(erro) {
        falhaConsulta = true;
        $('#estadoInventario').textContent = erro.message;
        $('#listaLotes').innerHTML='<tr><td colspan="6" class="estado-vazio">Não foi possível consultar o inventário.</td></tr>';
    } finally { carregando=false; if(repetir) {repetir=false;void carregar();} }
}
function exportar() {
    // Evita que nomes/códigos sejam interpretados como fórmulas na planilha.
    const celula = value => '"'+String(value??'').replace(/^[=+@\-\t\r]/,"'$&").replaceAll('"','""')+'"';
    const linhas=[['Produto','Código','Lote','Validade','Quantidade','Situação','Nível de alerta','Disponível para venda'],...filtrados.map(l=>[l.nome,l.codigoProduto,l.lote,l.validade||'',l.quantidade,l.situacao,l.nivelValidade,l.vendavel ? 'Sim' : 'Não'])];
    const url=URL.createObjectURL(new Blob(['\ufeff'+linhas.map(l=>l.map(celula).join(';')).join('\r\n')],{type:'text/csv;charset=utf-8'}));
    const a=document.createElement('a');a.href=url;a.download='inventario-validade.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
document.addEventListener('DOMContentLoaded',()=>{
    if(!estaAutenticado()) return;
    iniciarSincronizacao(carregar, 15000, { aoConexao(ativa) {
        conectado = ativa;
        if (!falhaConsulta && !carregando) $('#estadoInventario').textContent = estadoSincronizacao();
    } });
    $('#pesquisaEstoque').addEventListener('input',desenhar);
    $('#filtrosInventario').addEventListener('submit',ev=>{ev.preventDefault();if($('#validadeDe').value && $('#validadeAte').value && $('#validadeDe').value>$('#validadeAte').value){$('#estadoInventario').textContent='Confira o intervalo de validade.';return;}void carregar();});
    $('#limparInventario').addEventListener('click',()=>{$('#filtrosInventario').reset();void carregar();});
    $('#listaEstoque').addEventListener('click',async ev=>{
        const b=ev.target.closest('[data-controle]');if(!b)return;
        const p=produtos.find(p=>String(p.id)===b.dataset.controle);
        if(!confirm(p.controlaValidade?'Tornar a validade opcional para este produto? Lotes vencidos continuarão bloqueados.':'Exigir validade para este produto? Saldos sem data ficarão bloqueados até regularização.')) return;
        b.disabled=true;
        try {await apiService.put(`/inventario/produtos/${p.id}/controle-validade`,{controlaValidade:!p.controlaValidade});avisarMovimentacao('validade');await carregar();}catch(erro){$('#estadoInventario').textContent=erro.message;}finally{b.disabled=false;}
    });
    $('#listaLotes').addEventListener('click',ev=>{
        const b=ev.target.closest('[data-regularizar]');if(!b)return;
        const lote=lotes.find(l=>String(l.id)===b.dataset.regularizar);
        $('#loteId').value=lote.id;$('#codigoLote').value=lote.lote;$('#dataLote').value='';$('#erroLote').textContent='';$('#regularizarLote').showModal();
    });
    let regularizando = false;
    $('#formRegularizar').addEventListener('submit',async ev=>{
        ev.preventDefault();if(regularizando)return;
        const b=ev.submitter || ev.currentTarget.querySelector('button[type="submit"]');
        regularizando=true;b.disabled=true;
        try {await apiService.put(`/inventario/lotes/${$('#loteId').value}/validade`,{lote:$('#codigoLote').value,validade:$('#dataLote').value});avisarMovimentacao('validade');$('#regularizarLote').close();await carregar();}catch(erro){$('#erroLote').textContent=erro.message;}finally{regularizando=false;b.disabled=false;}
    });
    $('#cancelarLote').addEventListener('click',()=>$('#regularizarLote').close());
    $('#exportarInventario').addEventListener('click',exportar);
    $('#imprimirInventario').addEventListener('click',()=>{document.body.dataset.impressao='inventario';window.print();});
    window.addEventListener('afterprint',()=>delete document.body.dataset.impressao);
    void carregar();
});
