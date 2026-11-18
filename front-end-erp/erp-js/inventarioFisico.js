import { apiService } from './apiService.js';
import { estaAutenticado } from './authService.js';
import { escapeHtml as e } from './uiService.js';

const $ = s => document.querySelector(s);
let inventarios = [], atual = null;
const alteracoes = new Map();
const fmt = n => Number(n || 0).toLocaleString('pt-BR');
const moeda = n => Number(n || 0).toLocaleString('pt-BR',{style:'currency',currency:'BRL'});
const dataHora = v => v ? new Date(String(v).replace(' ','T')).toLocaleString('pt-BR') : '—';

function renderResumo(i) {
    if (!i) { $('#resumoInventarioFisico').innerHTML=''; return; }
    const pendentes = Number(i.totalProdutos)-Number(i.itensContados);
    $('#resumoInventarioFisico').innerHTML = `
      <div class="inventario-resumo-grade">
        <div><small>Produtos contados</small><strong>${fmt(i.itensContados)} / ${fmt(i.totalProdutos)}</strong></div>
        <div><small>Divergências</small><strong>${fmt(i.divergencias)}</strong></div>
        <div><small>Unidades no sistema</small><strong>${fmt(i.unidadesSistema)}</strong></div>
        <div><small>Unidades contadas</small><strong>${fmt(i.unidadesContadas)}</strong></div>
        <div><small>Valor estimado das diferenças</small><strong>${moeda(i.valorDiferenca)}</strong></div>
        <div><small>Situação</small><strong>${e(i.status==='CONCLUIDO'?'Concluído':`${pendentes} produto(s) pendente(s)`)}</strong></div>
      </div>`;
}
function renderItens(i) {
    const tbody=$('#listaItensInventarioFisico');
    if (!i) {tbody.innerHTML='<tr><td colspan="7" class="estado-vazio">Nenhum inventário selecionado.</td></tr>';$('#aprovarInventarioFisico').disabled=true;$('#imprimirInventarioFisico').disabled=true;return;}
    const fechado=i.status==='CONCLUIDO';
    tbody.innerHTML=i.itens.map(x=>{
      const contou=x.quantidadeContada!==null && x.quantidadeContada!==undefined;
      const dif=contou?Number(x.diferenca):null;
      const situacao=!contou?'Não contado':dif===0?'Conferido':dif<0?'Falta':'Sobra';
      const classe=!contou?'':dif===0?'status-ok':dif<0?'status-erro':'status-alerta';
      const pendente=alteracoes.get(String(x.itemId));
      return `<tr data-item="${e(x.itemId)}"><td>${e(x.nome)}<small>${e(x.codigo||'Sem código')}</small></td>
        <td>${fmt(x.quantidadeSistema)} un.</td>
        <td><input class="quantidade-contada" type="number" min="0" step="1" inputmode="numeric" aria-label="Quantidade física de ${e(x.nome)}" value="${pendente?e(pendente.quantidade):contou?e(x.quantidadeContada):''}" placeholder="Contar" ${fechado?'disabled':''}/></td>
        <td><span class="status ${classe}">${dif===null?'Não contado':`${dif>0?'+':''}${fmt(dif)} · ${situacao}`}</span></td>
        <td>${contou?moeda(x.valorDiferenca):'—'}</td>
        <td><input class="observacao-contagem" maxlength="500" aria-label="Observação de ${e(x.nome)}" value="${e(pendente?pendente.observacao:x.observacao||'')}" placeholder="Motivo / observação" ${fechado?'disabled':''}/></td>
        <td>${fechado?'Finalizado':`<button class="botao secundario salvar-contagem" type="button">Salvar</button>`}</td></tr>`;
    }).join('') || '<tr><td colspan="7" class="estado-vazio">Nenhum produto ativo com estoque cadastrado foi encontrado.</td></tr>';
    $('#aprovarInventarioFisico').disabled=fechado || alteracoes.size>0 || Number(i.itensContados)!==Number(i.totalProdutos) || Number(i.totalProdutos)===0;
    $('#aprovarInventarioFisico').textContent=fechado?'Inventário finalizado':'Conferir e aplicar ajustes';
    $('#imprimirInventarioFisico').disabled=false;
}
async function carregarLista(selecionarId=null) {
    inventarios=await apiService.get('/inventarios-fisicos');
    const select=$('#seletorInventarioFisico'), anterior=selecionarId??select.value;
    select.innerHTML='<option value="">Selecione um inventário</option>'+inventarios.map(i=>`<option value="${e(i.id)}">#${e(i.id)} · ${e(i.status==='CONCLUIDO'?'Concluído':'Em contagem')} · ${dataHora(i.criadoEm)} · ${e(i.responsavel||'')}</option>`).join('');
    if(anterior && inventarios.some(i=>String(i.id)===String(anterior))) select.value=String(anterior);
    else if(inventarios.length) select.value=String(inventarios[0].id);
    if(select.value) await carregarAtual(Number(select.value));
    else {atual=null;renderResumo(null);renderItens(null);$('#estadoInventarioFisico').textContent='Inicie uma contagem para conferir o estoque físico.';}
}
async function carregarAtual(id) {
    atual=await apiService.get(`/inventarios-fisicos/${id}`);
    renderResumo(atual);renderItens(atual);
    $('#estadoInventarioFisico').textContent=`Inventário #${atual.id} · ${atual.status==='CONCLUIDO'?'Concluído em '+dataHora(atual.concluidoEm):'Aberto para contagem'} · Responsável: ${atual.responsavel||'—'}`;
}
function erro(m) {$('#estadoInventarioFisico').textContent=m?.message||'Não foi possível concluir a operação.';}
function descartarEdicoes() {
  if(alteracoes.size && !confirm('Existem contagens não salvas. Descartar essas edições?')) return false;
  alteracoes.clear();return true;
}

document.addEventListener('DOMContentLoaded',()=>{
  if(!estaAutenticado() || !$('#inventarioFisicoPainel')) return;
  $('#novoInventarioFisico').addEventListener('click',async()=>{
    const b=$('#novoInventarioFisico');b.disabled=true;
    try {
      if(!descartarEdicoes()) return;
      if(!confirm('Iniciar uma nova contagem? O sistema registrará o saldo atual de todos os produtos ativos com estoque cadastrado.')) return;
      const novo=await apiService.post('/inventarios-fisicos',{observacao:$('#observacaoNovoInventario').value.trim()});
      $('#observacaoNovoInventario').value='';await carregarLista(novo.id);$('#estadoInventarioFisico').textContent=`Inventário #${novo.id} criado. Conte todos os produtos e salve cada quantidade.`;
    } catch(ex){erro(ex);} finally {b.disabled=false;}
  });
  $('#atualizarInventariosFisicos').addEventListener('click',async()=>{if(!descartarEdicoes())return;try{await carregarLista();}catch(ex){erro(ex);}});
  $('#seletorInventarioFisico').addEventListener('change',async ev=>{if(!descartarEdicoes()){ev.target.value=atual?.id||'';return;}try{if(ev.target.value) await carregarAtual(Number(ev.target.value));else{atual=null;renderResumo(null);renderItens(null);}}catch(ex){erro(ex);}});
  $('#listaItensInventarioFisico').addEventListener('input',ev=>{
    const tr=ev.target.closest('tr[data-item]');if(!tr)return;
    alteracoes.set(tr.dataset.item,{quantidade:tr.querySelector('.quantidade-contada').value,observacao:tr.querySelector('.observacao-contagem').value});
    $('#aprovarInventarioFisico').disabled=true;
    $('#estadoInventarioFisico').textContent='Existem contagens não salvas. Salve cada linha antes de aprovar.';
  });
  $('#listaItensInventarioFisico').addEventListener('click',async ev=>{
    const b=ev.target.closest('.salvar-contagem');if(!b||!atual)return;
    const tr=b.closest('tr'),q=tr.querySelector('.quantidade-contada').value,obs=tr.querySelector('.observacao-contagem').value.trim();
    if(q===''||!Number.isInteger(Number(q))||Number(q)<0){$('#estadoInventarioFisico').textContent='Informe uma quantidade inteira igual ou maior que zero. Use zero se o produto não foi encontrado.';return;}
    b.disabled=true;
    try {atual=await apiService.put(`/inventarios-fisicos/${atual.id}/itens/${tr.dataset.item}`,{quantidadeContada:Number(q),observacao:obs});alteracoes.delete(tr.dataset.item);renderResumo(atual);renderItens(atual);$('#estadoInventarioFisico').textContent=`Contagem salva para ${tr.querySelector('td').textContent.trim()}.`;}catch(ex){erro(ex);}finally{b.disabled=false;}
  });
  $('#aprovarInventarioFisico').addEventListener('click',async()=>{
    if(!atual||atual.status==='CONCLUIDO'||alteracoes.size)return;
    if(!confirm('Aprovar o inventário e ajustar os saldos do ERP para as quantidades físicas contadas? A operação será registrada no histórico de movimentações e não poderá ser desfeita por esta tela.'))return;
    const b=$('#aprovarInventarioFisico');b.disabled=true;
    try {atual=await apiService.post(`/inventarios-fisicos/${atual.id}/aprovar`,{});renderResumo(atual);renderItens(atual);await carregarLista(atual.id);$('#estadoInventarioFisico').textContent=`Inventário #${atual.id} finalizado. Os ajustes foram registrados no histórico do estoque.`;}catch(ex){erro(ex);}finally{b.disabled=atual?.status==='CONCLUIDO';}
  });
  $('#imprimirInventarioFisico').addEventListener('click',()=>{document.body.dataset.impressao='inventario-fisico';window.print();});
  window.addEventListener('afterprint',()=>delete document.body.dataset.impressao);
  carregarLista().catch(erro);
});
