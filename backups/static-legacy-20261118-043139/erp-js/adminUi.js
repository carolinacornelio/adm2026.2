/** Utilitários de interface das telas administrativas. Todo texto vindo do servidor passa por esc(). */

export const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const moeda = (v) => Number(v || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
export const numero = (v, casas = 2) => Number(v || 0).toLocaleString('pt-BR', { maximumFractionDigits: casas });
export function data(v) {
    if (!v) return '—';
    const p = String(v).slice(0, 10).split('-');
    return p.length === 3 ? `${p[2]}/${p[1]}/${p[0]}` : esc(v);
}
export function dataHora(v) { return v ? new Date(v).toLocaleString('pt-BR') : '—'; }

export function mensagem(el, texto, tipo = 'erro') {
    if (!el) return;
    el.textContent = texto || ''; el.dataset.tipo = tipo; el.hidden = !texto;
    el.setAttribute('role', tipo === 'erro' ? 'alert' : 'status');
}
export const carregando = (el, texto = 'Carregando...') => { el.innerHTML = `<p class="estado-vazio" role="status">${esc(texto)}</p>`; };
export const vazio = (el, texto) => { el.innerHTML = `<p class="estado-vazio">${esc(texto)}</p>`; };
export const selo = (texto, tipo = 'neutro') => `<span class="status status-${tipo}">${esc(texto)}</span>`;
export const barra = (p) => { const v = Math.max(0, Math.min(100, Number(p || 0))); return `<div class="progresso" role="progressbar" aria-valuenow="${v}" aria-valuemin="0" aria-valuemax="100"><span style="width:${v}%"></span></div><small>${numero(v)}%</small>`; };
export const confirmar = (texto) => Promise.resolve(window.confirm(texto));

/** Mostra uma ação assíncrona: bloqueia o botão, exibe erro no elemento de mensagem e confirma o sucesso. */
export async function executar(botao, areaMensagem, fn, sucesso) {
    if (botao) botao.disabled = true;
    try { await fn(); if (sucesso) mensagem(areaMensagem, sucesso, 'sucesso'); return true; }
    catch (e) { mensagem(areaMensagem, e.message || 'Não foi possível concluir a operação.', 'erro'); return false; }
    finally { if (botao) botao.disabled = false; }
}

/**
 * Modal de formulário genérico.
 * campos: { nome, rotulo, tipo: text|email|password|number|date|textarea|select|multicheck, valor, opcoes:[{valor,rotulo}], obrigatorio, ajuda, passo }
 * salvar(valores) é assíncrono; se lançar erro, a mensagem aparece no modal e ele continua aberto.
 */
export function abrirFormulario({ titulo, campos, salvar, textoSalvar = 'Salvar' }) {
    return new Promise((resolve) => {
        const dlg = document.createElement('dialog');
        dlg.className = 'historico-dialog dialogo-form';
        const corpo = campos.map((c, i) => {
            const id = `campo_${i}`, req = c.obrigatorio ? 'required' : '';
            let controle;
            if (c.tipo === 'textarea') controle = `<textarea id="${id}" name="${esc(c.nome)}" rows="3" ${req}>${esc(c.valor)}</textarea>`;
            else if (c.tipo === 'select') controle = `<select id="${id}" name="${esc(c.nome)}" ${req}>${(c.opcoes || []).map((o) => `<option value="${esc(o.valor)}" ${String(o.valor) === String(c.valor ?? '') ? 'selected' : ''}>${esc(o.rotulo)}</option>`).join('')}</select>`;
            else if (c.tipo === 'multicheck') controle = `<div class="chips" id="${id}">${(c.opcoes || []).map((o, k) => `<label class="chip"><input type="checkbox" name="${esc(c.nome)}" data-k="${k}" ${(c.valor || []).map(String).includes(String(o.valor)) ? 'checked' : ''}/> ${esc(o.rotulo)}</label>`).join('') || '<small>Nenhuma opção disponível.</small>'}</div>`;
            else controle = `<input id="${id}" name="${esc(c.nome)}" type="${c.tipo || 'text'}" value="${esc(c.valor)}" ${c.tipo === 'number' ? `step="${c.passo || 'any'}"` : ''} ${req} autocomplete="off"/>`;
            return `<div class="campo"><label for="${id}">${esc(c.rotulo)}${c.obrigatorio ? ' *' : ''}</label>${controle}${c.ajuda ? `<small class="ajuda-campo">${esc(c.ajuda)}</small>` : ''}</div>`;
        }).join('');
        dlg.innerHTML = `<form class="formulario" method="dialog" novalidate><div class="historico-topo"><h2>${esc(titulo)}</h2></div>${corpo}<p class="mensagem-formulario" hidden></p><div class="botoes"><button type="button" class="botao secundario" data-cancelar>Cancelar</button><button type="submit" class="botao primario">${esc(textoSalvar)}</button></div></form>`;
        document.body.appendChild(dlg);
        const fechar = (v) => { dlg.close(); dlg.remove(); resolve(v); };
        dlg.querySelector('[data-cancelar]').addEventListener('click', () => fechar(false));
        dlg.addEventListener('cancel', (e) => { e.preventDefault(); fechar(false); });
        const form = dlg.querySelector('form'), msg = dlg.querySelector('.mensagem-formulario');
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!form.reportValidity()) return;
            const valores = {};
            campos.forEach((c, i) => {
                if (c.tipo === 'multicheck') valores[c.nome] = [...dlg.querySelectorAll(`#campo_${i} input:checked`)].map((x) => c.opcoes[Number(x.dataset.k)].valor);
                else { const v = dlg.querySelector(`#campo_${i}`).value; valores[c.nome] = v; }
            });
            const botao = form.querySelector('[type=submit]');
            botao.disabled = true;
            try { await salvar(valores); fechar(true); }
            catch (erro) { mensagem(msg, erro.message || 'Não foi possível salvar.', 'erro'); botao.disabled = false; }
        });
        dlg.showModal();
        dlg.querySelector('input,select,textarea')?.focus();
    });
}

/** Converte campo de texto vazio em null (opcional) ou em número. */
export const nuloSeVazio = (v) => (v === '' || v == null ? null : v);
export const paraNumero = (v) => (v === '' || v == null ? null : Number(v));
