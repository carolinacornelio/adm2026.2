/** Avisos entre abas complementam os eventos autenticados enviados pelo servidor. */
import { apiService } from './apiService.js';
import { getUsuarioLogado } from './authService.js';

let canalPublicacao;
let empresaPublicacao;

function abrirCanal(empresaId) {
    if (!empresaId || !('BroadcastChannel' in window)) return null;
    try { return new BroadcastChannel(`erp-estoque:${empresaId}`); }
    catch { return null; } // O stream e a consulta periódica continuam disponíveis.
}

/** Chamar somente depois de a API confirmar a gravação da operação. */
export function avisarMovimentacao(origem) {
    const empresaId = getUsuarioLogado()?.empresaId;
    if (empresaPublicacao !== empresaId || !canalPublicacao) {
        canalPublicacao?.close();
        canalPublicacao = abrirCanal(empresaId);
        empresaPublicacao = empresaId;
    }
    try { canalPublicacao?.postMessage({ empresaId, origem, em: Date.now() }); }
    catch { /* Aviso local de melhor esforço; nunca altera o resultado da compra/venda. */ }
}

/** O aviso contém apenas a necessidade de consultar novamente a API da própria sessão. */
export function aoMovimentar(callback) {
    const empresaId = getUsuarioLogado()?.empresaId;
    const canal = abrirCanal(empresaId);
    const receber = evento => {
        if (evento.data?.empresaId === empresaId && getUsuarioLogado()?.empresaId === empresaId) callback(evento.data);
    };
    canal?.addEventListener('message', receber);
    return () => canal?.close();
}

/** Produtos e estoque: eventos do servidor, avisos locais e consulta de segurança sem F5. */
export function iniciarSincronizacao(recarregar, intervaloMs = 15000, { aoConexao } = {}) {
    const token = sessionStorage.getItem('authToken');
    const empresaId = getUsuarioLogado()?.empresaId;
    let encerrado = false, atualizando = false, pendente = false;
    let stream, reconexao, atualizacao;
    let atraso = 1000;
    let conectado = false;
    function informarConexao(ativa) {
        if (conectado === ativa) return;
        conectado = ativa;
        aoConexao?.(ativa);
    }
    const mesmaSessao = () => token && token === sessionStorage.getItem('authToken')
        && empresaId === getUsuarioLogado()?.empresaId;

    async function atualizar() {
        atualizacao = null;
        if (encerrado || document.hidden) return;
        if (!mesmaSessao()) { encerrar(); return; }
        if (atualizando) { pendente = true; return; }
        atualizando = true;
        try { await recarregar(); }
        catch { /* A tela trata erros de consulta; a próxima atualização tentará novamente. */ }
        finally {
            atualizando = false;
            if (pendente) { pendente = false; solicitar(); }
        }
    }
    function solicitar() {
        if (!encerrado && !atualizacao && !document.hidden) atualizacao = setTimeout(atualizar, 50);
    }
    async function conectar() {
        if (encerrado || document.hidden || stream) return;
        if (!mesmaSessao()) { encerrar(); return; }
        const controller = new AbortController();
        stream = controller;
        try {
            await apiService.events('/pos/eventos', {
                signal: controller.signal,
                onEvent: tipo => {
                    if (!mesmaSessao()) { encerrar(); return; }
                    if (tipo === 'conectado') informarConexao(true);
                    if (['conectado', 'estoque-atualizado', 'catalogo-atualizado'].includes(tipo)) {
                        atraso = 1000;
                        solicitar();
                    }
                },
            });
        } catch (erro) {
            if (!mesmaSessao()) { encerrar(); return; }
            if (erro.status === 401) { encerrar(); window.location.href = 'login.html'; }
        } finally {
            informarConexao(false);
            if (stream === controller) stream = null;
            if (!encerrado && !document.hidden) {
                reconexao = setTimeout(conectar, atraso);
                atraso = Math.min(atraso * 2, 15000);
            }
        }
    }
    function visibilidade() {
        clearTimeout(reconexao);
        if (document.hidden) stream?.abort();
        else { solicitar(); void conectar(); }
    }
    function restaurar(evento) { if (evento.persisted) window.location.reload(); }
    const cancelarAvisos = aoMovimentar(solicitar);
    const consultaSeguranca = setInterval(solicitar, intervaloMs);
    function encerrar() {
        encerrado = true;
        clearInterval(consultaSeguranca);
        clearTimeout(reconexao);
        clearTimeout(atualizacao);
        stream?.abort();
        cancelarAvisos();
        window.removeEventListener('focus', solicitar);
        document.removeEventListener('visibilitychange', visibilidade);
        window.removeEventListener('pagehide', encerrar);
    }
    window.addEventListener('focus', solicitar);
    document.addEventListener('visibilitychange', visibilidade);
    window.addEventListener('pagehide', encerrar);
    window.addEventListener('pageshow', restaurar);
    void conectar();
    return encerrar;
}

window.addEventListener('pagehide', () => { canalPublicacao?.close(); canalPublicacao = null; });
