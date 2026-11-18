/**
 * estoqueSync.js
 * ---------------------------------------------------------------------------
 * Regra de sincronização do estoque: "atualizar IMEDIATAMENTE após cada
 * transação de entrada/saída".
 *
 * Quem registra uma compra/venda chama avisarMovimentacao(); quem exibe saldos
 * (estoque.html, produtos-listagem.html) usa aoMovimentar() para recarregar
 * na hora, sem esperar polling nem F5.
 *
 * Usa BroadcastChannel (API nativa): entrega a mensagem para as OUTRAS abas
 * da mesma origem. É uma camada extra, independente do backend — o stream
 * /pos/eventos e o polling de segurança continuam funcionando.
 * ---------------------------------------------------------------------------
 */

const NOME_CANAL = 'erp-estoque';
const canal = 'BroadcastChannel' in window ? new BroadcastChannel(NOME_CANAL) : null;

/** Avisa as outras abas que o estoque mudou. origem: 'compra', 'venda', etc. */
export function avisarMovimentacao(origem) {
    try {
        canal?.postMessage({ origem, em: Date.now() });
    } catch (_) {
        // Melhor esforço: se falhar, o stream e o polling cobrem.
    }
}

/** Executa callback({ origem, em }) sempre que outra aba avisar uma movimentação. */
export function aoMovimentar(callback) {
    canal?.addEventListener('message', (evento) => callback(evento.data));
}
