import { estaAutenticado, getUsuarioLogado } from './authService.js';
import { apiService } from './apiService.js';
import { listarProdutos } from './produtosService.js';
import { listarPessoas } from './pessoasService.js';
import { posService } from './posService.js';
import { escapeHtml } from './uiService.js';
import { fromProdutoDTO } from './dto.js';
import { formatarDataHora, formatarHora } from './timeUtils.js';

const $ = (selector) => document.querySelector(selector);
const cents = (value) => Math.round(Number(value || 0) * 100);
const money = (value) => (value / 100).toLocaleString('pt-BR', { style:'currency', currency:'BRL' });
const methods = { DINHEIRO:'Dinheiro', PIX:'Pix', CREDITO:'Cartão de crédito', DEBITO:'Cartão de débito' };
function operationId() {
    // getRandomValues também funciona no acesso HTTP pela rede local.
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map((b) => b.toString(16).padStart(2,'0')).join('');
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
const blank = () => ({ cart:[], discount:0, customer:'', method:'DINHEIRO', received:'', held:[], pending:null });
let state = blank();
let products = [];
let customers = [];
let sending = false;
let refreshing = false;
let refreshQueued = false;
let ready = false;
let liveController;
let storageKey;
let staged = null;

function message(text, success = false) {
    $('#message').textContent = text;
    $('#message').dataset.tipo = success ? 'sucesso' : 'erro';
    $('#message').hidden = !text;
}
function handleError(error) {
    message(error.message || 'Não foi possível concluir a operação.');
    if (error.status === 401) window.location.href = 'login.html';
}
function persist(required = false) {
    try { sessionStorage.setItem(storageKey, JSON.stringify(state)); return true; }
    catch {
        message('Não foi possível guardar o estado desta venda no navegador. Libere o armazenamento antes de finalizar.');
        if (required) return false;
        return false;
    }
}
const locked = () => sending || Boolean(state.pending);
const subtotal = () => state.cart.reduce((sum, line) => sum + line.price * line.qty, 0);
const total = () => subtotal() - state.discount;
const product = (id) => products.find((p) => p.id === Number(id));
function restore() {
    try {
        const saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
        if (saved && Array.isArray(saved.cart) && Array.isArray(saved.held)) {
            state = { ...blank(), ...saved };
            if (!state.cart.every((line) => Number.isInteger(line.id) && Number.isInteger(line.qty) && line.qty > 0 && Number.isSafeInteger(line.price) && line.price >= 0)) state = blank();
            if (!Number.isSafeInteger(state.discount) || state.discount < 0 || !methods[state.method]) state = blank();
        }
    } catch { state = blank(); }
    $('#discount').value = (state.discount / 100).toFixed(2);
    $('#payment-method').value = state.method;
    $('#received').value = state.received;
}
function resetCurrent() {
    const held = state.held;
    state = { ...blank(), held };
    $('#customer').value = '';
    $('#discount').value = '0';
    $('#received').value = '';
    $('#payment-method').value = 'DINHEIRO';
    $('#paid').checked = false;
    persist();
    renderCart();
}
function invalidateConfirmation() {
    $('#paid').checked = false;
    staged = null;
}
function stockBadge(p) {
    const quantity = Math.max(0, Number(p.quantidadeEstoque) || 0);
    const minimum = Math.max(0, Number(p.estoqueMinimo) || 0);
    if (quantity === 0) return '<span class="status pos-estoque-zero">Sem estoque</span>';
    const nearMinimum = Number(p.limiteProximoMinimo ?? (minimum + Math.max(1, Math.ceil(minimum * 0.2))));
    const level = quantity <= minimum ? 'minimo' : quantity <= nearMinimum ? 'alerta' : 'normal';
    const description = fromProdutoDTO(p).situacao;
    return `<span class="status pos-estoque-${level}" title="${description}. Mínimo: ${minimum}" aria-label="${quantity} unidades. ${description}. Mínimo: ${minimum}">${quantity}</span>`;
}

function renderProducts() {
    const term = $('#search').value.trim().toLocaleLowerCase('pt-BR');
    const category = $('#category').value;
    const filtered = products.filter((p) => (!category || String(p.categoriaId ?? 'none') === category)
        && `${p.nome} ${p.codigo}`.toLocaleLowerCase('pt-BR').includes(term));
    $('#products').innerHTML = filtered.length ? filtered.map((p) => `<tr><td>${escapeHtml(p.nome)}<small>${escapeHtml(p.codigo || 'Sem código')}</small></td><td>${money(cents(p.precoVenda))}</td><td>${stockBadge(p)}</td><td><button class="botao secundario" data-add="${p.id}" ${locked() || !ready || p.quantidadeEstoque <= 0 ? 'disabled' : ''} aria-label="Adicionar ${escapeHtml(p.nome)}">+</button></td></tr>`).join('')
        : `<tr><td colspan="4" class="estado-vazio">${ready ? 'Nenhum produto encontrado.' : 'Catálogo indisponível. Aguarde a reconexão automática.'}</td></tr>`;
}
function renderTotals() {
    $('#subtotal').textContent = money(subtotal());
    $('#total').textContent = money(total());
    $('#discount').max = (subtotal() / 100).toFixed(2);
    $('#cash-fields').hidden = state.method !== 'DINHEIRO';
    $('#paid-confirmation').hidden = state.method === 'PIX';
    $('#change').textContent = money(Math.max(0, cents(state.received) - total()));
    $('#payment-help').textContent = state.method === 'DINHEIRO'
        ? 'Confirme o recebimento do dinheiro antes de finalizar.'
        : state.method === 'PIX' ? 'Ao confirmar a venda, o QR Code Pix será exibido antes do registro.'
        : 'Realize a cobrança na maquininha e confirme a aprovação antes de finalizar.';
}
function renderCart() {
    $('#cart').innerHTML = state.cart.length ? state.cart.map((line) => {
        const current = product(line.id);
        const unavailable = ready && (!current || current.quantidadeEstoque < line.qty);
        return `<tr><td>${escapeHtml(line.name)}<small class="${unavailable ? 'pos-invalido' : ''}">${unavailable ? 'Estoque insuficiente ou produto indisponível' : money(line.price) + ' / un.'}</small></td><td><div class="pos-quantidade"><button class="botao secundario" data-step="-1" data-id="${line.id}" ${locked() ? 'disabled' : ''} aria-label="Diminuir ${escapeHtml(line.name)}">−</button><input type="number" data-quantity="${line.id}" min="1" step="1" value="${line.qty}" ${locked() ? 'disabled' : ''} aria-label="Quantidade de ${escapeHtml(line.name)}"><button class="botao secundario" data-step="1" data-id="${line.id}" ${locked() ? 'disabled' : ''} aria-label="Aumentar ${escapeHtml(line.name)}">+</button></div></td><td>${money(line.price * line.qty)}</td><td><button class="botao-remover-item" data-remove="${line.id}" ${locked() ? 'disabled' : ''} aria-label="Remover ${escapeHtml(line.name)}">×</button></td></tr>`;
    }).join('') : '<tr><td colspan="4" class="estado-vazio">Adicione produtos para iniciar a venda.</td></tr>';
    $('#held-count').textContent = state.held.length;
    $('#pending-warning').hidden = !state.pending;
    $('#checkout').textContent = sending ? 'Registrando venda...' : state.pending ? 'Verificar venda / tentar novamente' : 'Finalizar venda (F9)';
    $('#checkout').disabled = sending || (!state.pending && (!ready || !state.cart.length));
    for (const selector of ['#customer','#discount','#payment-method','#received','#paid','#clear','#hold','#held-button']) $(selector).disabled = locked();
    $('#clear').disabled ||= !state.cart.length;
    $('#hold').disabled ||= !state.cart.length;
    renderTotals();
    renderProducts();
}
function synchronizeCart() {
    if (locked()) return;
    let changed = false;
    state.cart.forEach((line) => {
        const p = product(line.id);
        if (p) {
            const price = cents(p.precoVenda);
            changed ||= price !== line.price;
            line.price = price;
            line.name = p.nome;
        }
    });
    if (changed) { invalidateConfirmation(); message('Preços atualizados. Confira o total e confirme o recebimento novamente.'); }
    persist();
}
async function refresh() {
    if (sending) return;
    if (refreshing) { refreshQueued = true; return; }
    refreshing = true;
    try {
        const [catalog, people] = await Promise.all([listarProdutos(), listarPessoas()]);
        products = catalog;
        customers = people.filter((person) => person.tipo === 'CLIENTE' || person.tipo === 'CLIENTE_FORNECEDOR');
        ready = true;
        const selected = $('#category').value;
        const categories = new Map(products.map((p) => [String(p.categoriaId ?? 'none'), p.categoriaNome || 'Sem categoria']));
        $('#category').replaceChildren(new Option('Todas as categorias', ''), ...[...categories].map(([id,name]) => new Option(name,id)));
        if (categories.has(selected)) $('#category').value = selected;
        $('#customer').replaceChildren(new Option('Consumidor final', ''), ...customers.map((c) => new Option(c.nome, c.id)));
        $('#customer').value = state.customer;
        if (state.customer && !customers.some((c) => String(c.id) === String(state.customer)) && !locked()) {
            state.customer = '';
            invalidateConfirmation();
            message('O cliente selecionado não está mais disponível. Selecione outro ou use Consumidor final.');
        }
        synchronizeCart();
        renderCart();
        $('#sync-status').textContent = `Conectado · catálogo atualizado às ${formatarHora()}.`;
    } catch (error) {
        ready = false;
        $('#sync-status').textContent = 'Sem conexão com o ERP. Atualize o catálogo antes de iniciar outra venda.';
        renderCart();
        handleError(error);
    } finally { refreshing = false; if (refreshQueued) { refreshQueued = false; void refresh(); } }
}
const espera = (ms, signal) => new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once:true });
});
async function acompanharAtualizacoes() {
    if (liveController || document.hidden) return;
    const controller = new AbortController();
    liveController = controller;
    let atraso = 700;
    try {
        while (!controller.signal.aborted && !document.hidden) {
            try {
                await apiService.events('/pos/eventos', {
                    signal:controller.signal,
                    onEvent:(tipo) => {
                        if (tipo === 'conectado') {
                            $('#sync-status').textContent = 'Conectado · estoque atualizado em tempo real.';
                            void refresh();
                        } else if (tipo === 'estoque-atualizado' || tipo === 'catalogo-atualizado') {
                            void refresh();
                        }
                    },
                });
                atraso = 700;
            } catch (error) {
                if (controller.signal.aborted) break;
                $('#sync-status').textContent = 'Reconectando para receber as atualizações automáticas…';
                if (error.status === 401) { handleError(error); break; }
                await espera(atraso, controller.signal);
                atraso = Math.min(atraso * 2, 5000);
            }
        }
    } finally {
        if (liveController === controller) liveController = null;
    }
}
function setQuantity(id, quantity) {
    if (locked()) return;
    const p = product(id);
    if (!Number.isInteger(quantity) || quantity < 0 || (quantity > 0 && (!p || quantity > p.quantidadeEstoque || quantity > 1000000))) {
        message('Quantidade inválida ou estoque insuficiente.');
        renderCart();
        return;
    }
    const line = state.cart.find((item) => item.id === Number(id));
    if (!quantity) state.cart = state.cart.filter((item) => item.id !== Number(id));
    else if (line) line.qty = quantity;
    else state.cart.push({ id:p.id, qty:quantity, name:p.nome, price:cents(p.precoVenda) });
    invalidateConfirmation();
    persist();
    renderCart();
}
function validateSale() {
    if (!ready || !state.cart.length) throw new Error('Atualize o catálogo e adicione pelo menos um produto.');
    if (state.cart.some((line) => !product(line.id) || line.qty > product(line.id).quantidadeEstoque)) throw new Error('Há produtos indisponíveis ou sem estoque suficiente. Revise os itens.');
    if (!Number.isSafeInteger(state.discount) || state.discount < 0 || state.discount > subtotal() || subtotal() > 9999999999) throw new Error('Informe um desconto e um total válidos.');
    if (!$('#discount').checkValidity()) throw new Error('Informe um desconto válido, com até duas casas decimais.');
    if (state.method !== 'PIX' && !$('#paid').checked) throw new Error('Confirme que recebeu o pagamento antes de finalizar.');
    if (state.method === 'DINHEIRO' && (!$('#received').checkValidity() || !state.received || cents(state.received) < total() || cents(state.received) > 9999999999)) throw new Error('O valor recebido precisa cobrir a venda e ter até duas casas decimais.');
    return {
        chaveOperacao:operationId(), clienteId:state.customer ? Number(state.customer) : null,
        itens:state.cart.map((line) => ({ produtoId:line.id, quantidade:line.qty })), desconto:state.discount / 100,
        totalEsperado:total() / 100, formaPagamento:state.method,
        valorRecebido:state.method === 'DINHEIRO' ? cents(state.received) / 100 : total() / 100, pagamentoConfirmado:true,
    };
}
function requestCheckout() {
    if (sending) return;
    if (state.pending) { submitSale(); return; }
    try {
        staged = validateSale();
        $('#confirm-summary').textContent = `${money(total())} · ${methods[state.method]} · ${$('#customer').selectedOptions[0]?.textContent || 'Consumidor final'}`;
        $('#confirm-sale').textContent = state.method === 'PIX' ? 'Exibir QR Code Pix' : 'Confirmar e registrar';
        $('#confirm-dialog').showModal();
    } catch (error) { handleError(error); }
}
async function submitSale() {
    if (sending || (!state.pending && !staged)) return;
    if (!state.pending) {
        state.pending = staged;
        if (!persist(true)) { state.pending = null; return; }
    }
    $('#confirm-dialog').close();
    $('#pix-dialog').close();
    sending = true;
    renderCart();
    try {
        const receipt = await posService.finalizar(state.pending);
        if (!receipt?.id || !Array.isArray(receipt.itens)) throw new Error('Não foi possível confirmar o retorno do servidor. Verifique a mesma venda novamente.');
        resetCurrent();
        staged = null;
        showReceipt(receipt);
        message(`Venda #${receipt.id} registrada no ERP.`, true);
    } catch (error) {
        // Uma resposta perdida ou falha 5xx mantém o mesmo payload e UUID para a próxima tentativa.
        if (error.status === 400 || error.status === 409) { state.pending = null; staged = null; invalidateConfirmation(); persist(); }
        handleError(error);
    } finally {
        sending = false;
        renderCart();
        if (!state.pending) await refresh();
    }
}
function showReceipt(receipt) {
    const e = escapeHtml;
    $('#receipt').innerHTML = `<div class="pos-recibo"><h3>${e(receipt.empresa)}</h3><p>Venda #${receipt.id} · ${e(formatarDataHora(receipt.data, 'Data indisponível'))}</p><p>Cliente: ${e(receipt.cliente)}<br>Operador: ${e(receipt.operador)}</p><div class="pos-recibo-itens">${receipt.itens.map((line) => `<div class="pos-recibo-linha"><span>${line.quantidade} × ${e(line.nome)}<small>${money(cents(line.precoUnitario))} por unidade</small></span><strong>${money(cents(line.subtotal))}</strong></div>`).join('')}</div><div class="pos-recibo-linha"><span>Subtotal</span><span>${money(cents(receipt.subtotal))}</span></div><div class="pos-recibo-linha"><span>Desconto</span><span>${money(cents(receipt.desconto))}</span></div><div class="pos-recibo-linha"><strong>Total</strong><strong>${money(cents(receipt.total))}</strong></div><p>Pagamento: ${e(methods[receipt.formaPagamento] || receipt.formaPagamento)}<br>Valor informado: ${money(cents(receipt.valorRecebido))}<br>Troco: ${money(cents(receipt.troco))}</p><p>Recebimento confirmado pelo operador.<br>Comprovante não fiscal. Não substitui documento fiscal.</p></div>`;
    $('#receipt-dialog').showModal();
}
function snapshot() { return { cart:state.cart.map((line) => ({ ...line })), discount:state.discount, customer:state.customer }; }
function hold() {
    if (locked() || !state.cart.length) return;
    state.held.push(snapshot());
    resetCurrent();
    message('Venda guardada nesta aba. O estoque não foi reservado.', true);
}
function renderHeld() {
    $('#held-list').innerHTML = state.held.length ? state.held.map((sale,index) => `<div class="pos-held-row"><div><strong>Venda guardada ${index+1}</strong><small>${sale.cart.reduce((sum,line) => sum+line.qty,0)} itens · ${money(sale.cart.reduce((sum,line) => sum+line.price*line.qty,0)-sale.discount)}</small></div><button class="botao secundario" data-resume="${index}">Retomar</button></div>`).join('') : '<p class="estado-vazio">Nenhuma venda guardada.</p>';
}
async function history() {
    $('#history').innerHTML = '<tr><td colspan="5">Carregando...</td></tr>';
    $('#history-dialog').showModal();
    try {
        const sales = await posService.listar();
        $('#history').innerHTML = sales.length ? sales.map((sale) => `<tr><td>#${sale.id}</td><td>${escapeHtml(formatarDataHora(sale.data, 'Data indisponível'))}</td><td>${escapeHtml(sale.cliente)}</td><td>${money(cents(sale.total))}</td><td><button class="botao secundario" data-receipt="${sale.id}">Comprovante</button></td></tr>`).join('') : '<tr><td colspan="5" class="estado-vazio">Nenhuma venda POS registrada.</td></tr>';
    } catch (error) { $('#history-dialog').close(); handleError(error); }
}
function configure() {
    $('#search').addEventListener('input', renderProducts);
    $('#category').addEventListener('change', renderProducts);
    $('#search-form').addEventListener('submit', (event) => {
        event.preventDefault();
        if (locked() || !ready) return;
        const term = $('#search').value.trim();
        const matches = products.filter((p) => p.codigo === term);
        if (matches.length !== 1) return message('Leia um código exato ou adicione o produto pela lista.');
        const p = matches[0];
        setQuantity(p.id, (state.cart.find((line) => line.id === p.id)?.qty || 0)+1);
        $('#search').value = '';
        renderProducts();
    });
    $('#products').addEventListener('click', (event) => {
        const button = event.target.closest('[data-add]');
        if (button) setQuantity(Number(button.dataset.add), (state.cart.find((line) => line.id === Number(button.dataset.add))?.qty || 0)+1);
    });
    $('#cart').addEventListener('click', (event) => {
        const step = event.target.closest('[data-step]');
        const remove = event.target.closest('[data-remove]');
        if (step) { const line = state.cart.find((item) => item.id === Number(step.dataset.id)); setQuantity(line.id,line.qty+Number(step.dataset.step)); }
        if (remove) setQuantity(Number(remove.dataset.remove),0);
    });
    $('#cart').addEventListener('change', (event) => { if (event.target.matches('[data-quantity]')) setQuantity(Number(event.target.dataset.quantity),Number(event.target.value)); });
    $('#discount').addEventListener('input', () => { if (locked()) return; state.discount = cents($('#discount').value); invalidateConfirmation(); persist(); renderTotals(); });
    $('#received').addEventListener('input', () => { if (locked()) return; state.received = $('#received').value; invalidateConfirmation(); persist(); renderTotals(); });
    $('#payment-method').addEventListener('change', () => { if (locked()) return; state.method = $('#payment-method').value; invalidateConfirmation(); persist(); renderTotals(); });
    $('#customer').addEventListener('change', () => { if (locked()) return; state.customer = $('#customer').value; invalidateConfirmation(); persist(); });
    $('#clear').addEventListener('click', () => { if (!locked() && state.cart.length) $('#clear-dialog').showModal(); });
    $('#confirm-clear').addEventListener('click', () => { if (!locked()) resetCurrent(); $('#clear-dialog').close(); });
    $('#checkout').addEventListener('click', requestCheckout);
    $('#confirm-sale').addEventListener('click', () => {
        if (staged?.formaPagamento === 'PIX') {
            $('#pix-total').textContent = money(cents(staged.totalEsperado));
            $('#confirm-dialog').close();
            $('#pix-dialog').showModal();
            return;
        }
        void submitSale();
    });
    $('#confirm-pix').addEventListener('click', () => {
        if (staged?.formaPagamento !== 'PIX') return;
        $('#paid').checked = true;
        void submitSale();
    });
    $('#confirm-dialog').addEventListener('cancel', () => { staged = null; });
    $('#pix-dialog').addEventListener('cancel', () => { staged = null; });
    $('#hold').addEventListener('click', hold);
    $('#held-button').addEventListener('click', () => { if (locked()) return; renderHeld(); $('#held-dialog').showModal(); });
    $('#held-list').addEventListener('click', (event) => {
        const button = event.target.closest('[data-resume]');
        if (!button || locked()) return;
        const [sale] = state.held.splice(Number(button.dataset.resume),1);
        if (state.cart.length) state.held.push(snapshot());
        state.cart = sale.cart;
        state.discount = sale.discount;
        state.customer = sale.customer;
        state.received = '';
        $('#customer').value = state.customer;
        $('#received').value = '';
        $('#discount').value = (state.discount/100).toFixed(2);
        invalidateConfirmation();
        synchronizeCart();
        persist();
        renderCart();
        $('#held-dialog').close();
        message('Venda retomada. Confira os itens e confirme o recebimento.', true);
    });
    $('#history-button').addEventListener('click', history);
    $('#history').addEventListener('click', async (event) => {
        const button = event.target.closest('[data-receipt]');
        if (!button || button.disabled) return;
        button.disabled = true;
        try { const receipt = await posService.buscar(button.dataset.receipt); $('#history-dialog').close(); showReceipt(receipt); }
        catch (error) { handleError(error); }
        finally { button.disabled = false; }
    });
    document.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', () => { button.closest('dialog').close(); staged = null; }));
    $('#print').addEventListener('click', () => window.print());
    $('#new-sale').addEventListener('click', () => { $('#receipt-dialog').close(); $('#search').focus(); });
    document.addEventListener('keydown', (event) => {
        if (document.querySelector('dialog[open]') || event.ctrlKey || event.altKey || event.metaKey) return;
        if (event.key === 'F2') { event.preventDefault(); $('#search').focus(); $('#search').select(); }
        if (event.key === 'F9') { event.preventDefault(); requestCheckout(); }
    });
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) liveController?.abort();
        else { void refresh(); void acompanharAtualizacoes(); }
    });
}
if (estaAutenticado()) {
    const user = getUsuarioLogado();
    if (!user?.empresaId || !user?.id) window.location.href = 'login.html';
    else {
        storageKey = `erp-pos:${user.empresaId}:${user.id}`;
        restore();
        configure();
        renderCart();
        void refresh();
        void acompanharAtualizacoes();
    }
}
