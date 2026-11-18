import * as api from './financeiroService.js';
import { carregarPermissoes, pode } from './permissoesService.js';
import { esc, data, dataHora, moeda, mensagem, carregando, vazio, selo, abrirFormulario, confirmar } from './adminUi.js';

const msg = document.getElementById('msg'), conteudo = document.getElementById('conteudo'), acoes = document.getElementById('acoesPagina');
let info, tipo = 'receber', categorias = [];
const $ = (i) => document.getElementById(i);
const rotulo = () => (tipo === 'pagar' ? { pagar: 'Pagar', liquidado: 'Pago', nova: '+ Nova conta a pagar' } : { pagar: 'Receber', liquidado: 'Recebido', nova: '+ Nova conta a receber' });

conteudo.innerHTML = `<div id="resumo"></div>
 <div class="abas"><button type="button" class="botao secundario" data-tipo="receber" aria-pressed="true">A receber</button><button type="button" class="botao secundario" data-tipo="pagar" aria-pressed="false">A pagar</button></div>
 <div class="grade-filtros">
  <div class="campo"><label for="fSit">Situação</label><select id="fSit"><option value="">Todas</option><option value="PENDENTE">Pendentes</option><option value="VENCIDO">Vencidas</option><option value="LIQUIDADO">Liquidadas</option><option value="CANCELADO">Canceladas</option></select></div>
  <div class="campo"><label for="fDe">Vencimento de</label><input id="fDe" type="date"/></div><div class="campo"><label for="fAte">até</label><input id="fAte" type="date"/></div>
  <div class="campo"><label for="fBusca">Descrição</label><input id="fBusca" type="search"/></div></div><div id="lista"></div>`;
const novo = document.createElement('button'); novo.className = 'botao primario'; novo.hidden = true; acoes.appendChild(novo);
const novaCat = document.createElement('button'); novaCat.className = 'botao secundario'; novaCat.textContent = '+ Categoria'; novaCat.hidden = true; acoes.appendChild(novaCat);

async function resumo() {
    try {
        const r = await api.resumoFinanceiro();
        $('resumo').innerHTML = `<div class="cartoes">
          <div class="cartao"><small>A receber (pendente)</small><div class="indicador">${moeda(r.receber.pendente)}</div><small>Vencido: ${moeda(r.receber.vencido)}</small></div>
          <div class="cartao"><small>A pagar (pendente)</small><div class="indicador">${moeda(r.pagar.pendente)}</div><small>Vencido: ${moeda(r.pagar.vencido)}</small></div>
          <div class="cartao"><small>Saldo previsto</small><div class="indicador">${moeda(r.saldoPrevisto)}</div><small>Receber − pagar</small></div>
          <div class="cartao"><small>Recebido no mês</small><div class="indicador">${moeda(r.receber.liquidadoNoMes)}</div></div>
          <div class="cartao"><small>Pago no mês</small><div class="indicador">${moeda(r.pagar.liquidadoNoMes)}</div></div></div>`;
    } catch (e) { $('resumo').innerHTML = ''; mensagem(msg, e.message); }
}

async function listar() {
    carregando($('lista'));
    novo.textContent = rotulo().nova;
    try {
        const lista = await api.listarLancamentos(tipo, { situacao: $('fSit').value, de: $('fDe').value, ate: $('fAte').value, busca: $('fBusca').value.trim() });
        if (!lista.length) return vazio($('lista'), 'Nenhum lançamento encontrado.');
        const upd = pode(info, 'finance.update');
        const tipoSelo = { pendente: 'alerta', vencido: 'erro', cancelado: 'neutro' };
        $('lista').innerHTML = `<div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Descrição</th><th>Categoria</th><th>Vencimento</th><th>Valor</th><th>Situação</th><th>Origem</th><th>Ações</th></tr></thead><tbody>${lista.map((l) => `
          <tr><td>${esc(l.descricao)}</td><td>${esc(l.categoriaNome || '—')}</td><td>${data(l.vencimento)}</td><td>${moeda(l.valor)}</td>
          <td>${selo(l.situacao === 'pago' || l.situacao === 'recebido' ? rotulo().liquidado : l.situacao.charAt(0).toUpperCase() + l.situacao.slice(1), tipoSelo[l.situacao] || 'ok')}</td><td>${esc(l.origem)}</td>
          <td class="acoes-tabela"><button class="acao-mini" data-hist="${l.id}">Histórico</button>${upd && l.status === 'pendente' ? `<button class="acao-mini" data-liq="${l.id}">${rotulo().pagar}</button><button class="acao-mini" data-edit="${l.id}">Editar</button><button class="acao-mini excluir" data-canc="${l.id}">Cancelar</button>` : ''}</td></tr>`).join('')}</tbody></table></div>`;
        const item = (b) => lista.find((l) => l.id == b);
        $('lista').querySelectorAll('[data-liq]').forEach((b) => b.onclick = async () => { if (await confirmar(`Confirmar: ${rotulo().liquidado.toLowerCase()} hoje?`)) agir(() => api.liquidarLancamento(tipo, b.dataset.liq), 'Lançamento liquidado.'); });
        $('lista').querySelectorAll('[data-canc]').forEach((b) => b.onclick = async () => { if (await confirmar('Cancelar este lançamento? Ele permanece no histórico.')) agir(() => api.cancelarLancamento(tipo, b.dataset.canc, ''), 'Lançamento cancelado.'); });
        $('lista').querySelectorAll('[data-edit]').forEach((b) => b.onclick = () => formulario(item(b.dataset.edit)));
        $('lista').querySelectorAll('[data-hist]').forEach((b) => b.onclick = () => historico(b.dataset.hist));
    } catch (e) { mensagem(msg, e.message); $('lista').innerHTML = ''; }
}
async function agir(fn, ok) { try { await fn(); mensagem(msg, ok, 'sucesso'); resumo(); listar(); } catch (e) { mensagem(msg, e.message); } }

async function formulario(l) {
    const auto = l && l.origem !== 'MANUAL';
    const ok = await abrirFormulario({ titulo: l ? 'Editar lançamento' : rotulo().nova.replace('+ ', ''), campos: [
        { nome: 'descricao', rotulo: 'Descrição', valor: l?.descricao, obrigatorio: true },
        { nome: 'valor', rotulo: 'Valor (R$)', tipo: 'number', passo: '0.01', valor: l?.valor, obrigatorio: true, ajuda: auto ? 'Valor de lançamentos gerados por compras/vendas não pode ser alterado.' : 'Até duas casas decimais.' },
        { nome: 'vencimento', rotulo: 'Vencimento', tipo: 'date', valor: l ? String(l.vencimento).slice(0, 10) : '', obrigatorio: true },
        { nome: 'categoriaId', rotulo: 'Categoria', tipo: 'select', valor: l?.categoriaId ?? '', opcoes: [{ valor: '', rotulo: 'Sem categoria' }, ...categorias.filter((c) => c.ativo).map((c) => ({ valor: c.id, rotulo: c.nome }))] },
        { nome: 'observacao', rotulo: 'Observação', tipo: 'textarea', valor: l?.observacao }],
        salvar: (v) => { const c = { ...v, categoriaId: v.categoriaId ? Number(v.categoriaId) : null }; return l ? api.atualizarLancamento(tipo, l.id, c) : api.criarLancamento(tipo, c); } });
    if (ok) { mensagem(msg, 'Lançamento salvo.', 'sucesso'); resumo(); listar(); }
}

async function historico(id) {
    try {
        const c = await api.buscarLancamento(tipo, id);
        const dlg = document.createElement('dialog'); dlg.className = 'historico-dialog dialogo-form';
        dlg.innerHTML = `<div class="historico-topo"><h2>Histórico: ${esc(c.descricao)}</h2></div><div class="tabela-rolavel"><table class="tabela"><thead><tr><th>Quando</th><th>Ação</th><th>Valor</th><th>Quem</th><th>Detalhe</th></tr></thead><tbody>${c.historico.map((h) => `<tr><td>${dataHora(h.criadoEm)}</td><td>${esc(h.acao)}</td><td>${h.valor == null ? '—' : moeda(h.valor)}</td><td>${esc(h.usuarioNome || '—')}</td><td>${esc(h.detalhe)}</td></tr>`).join('')}</tbody></table></div><div class="botoes"><button class="botao secundario" type="button">Fechar</button></div>`;
        document.body.appendChild(dlg); dlg.querySelector('button').onclick = () => { dlg.close(); dlg.remove(); }; dlg.showModal();
    } catch (e) { mensagem(msg, e.message); }
}

async function categoria() {
    const ok = await abrirFormulario({ titulo: 'Nova categoria financeira', campos: [
        { nome: 'nome', rotulo: 'Nome', obrigatorio: true },
        { nome: 'tipo', rotulo: 'Tipo', tipo: 'select', valor: tipo, opcoes: [{ valor: 'receber', rotulo: 'A receber' }, { valor: 'pagar', rotulo: 'A pagar' }] }],
        salvar: (v) => api.criarCategoriaFinanceira(v) });
    if (ok) { mensagem(msg, 'Categoria criada.', 'sucesso'); categorias = await api.listarCategoriasFinanceiras(tipo); }
}

(async () => {
    info = await carregarPermissoes();
    if (pode(info, 'finance.create')) { novo.hidden = false; novaCat.hidden = false; novo.onclick = () => formulario(null); novaCat.onclick = categoria; }
    async function trocar(t) {
        tipo = t;
        conteudo.querySelectorAll('[data-tipo]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.tipo === t)));
        try { categorias = await api.listarCategoriasFinanceiras(t); } catch { categorias = []; }
        listar();
    }
    conteudo.querySelectorAll('[data-tipo]').forEach((b) => b.onclick = () => trocar(b.dataset.tipo));
    ['fSit', 'fDe', 'fAte'].forEach((i) => $(i).addEventListener('change', listar));
    let t; $('fBusca').oninput = () => { clearTimeout(t); t = setTimeout(listar, 300); };
    resumo(); trocar('receber');
})();
