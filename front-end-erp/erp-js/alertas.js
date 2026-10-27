import { apiService } from './apiService.js';
import { estaAutenticado } from './authService.js';
import { escapeHtml } from './uiService.js';
import { formatarDataHora } from './timeUtils.js';
const nomes = { NORMAL:'Aviso', ALERTA:'Estoque mínimo', IMPORTANTE:'Importante', PRIORIDADE:'Prioridade de validade' };

function iniciar() {
    const cards = document.getElementById('alertasNovos');
    if (!estaAutenticado() || !cards || cards.dataset.inicializado) return;
    cards.dataset.inicializado = 'true';
    const painel = cards.closest('.dashboard-alertas');
    const estado = document.getElementById('alertasEstado');
    const lista = document.getElementById('alertasHistorico');
    const verificar = document.getElementById('atualizarAlertas');
    const mais = document.getElementById('maisAlertas');
    let pagina = 0, carregados = 0, ocupado = false, leituraEmAndamento = false;
    function registro(a, historico = false) {
        const data = a.criado_em ? formatarDataHora(a.criado_em, '') : '';
        return `<article class="alerta-registro ${!a.ativo ? 'alerta-resolvido' : ''}" data-nivel="${escapeHtml(a.nivel)}"><strong>${nomes[a.nivel] || 'Aviso'}</strong><p>${escapeHtml(a.mensagem)}</p>${!a.ativo ? '<small>Condição resolvida ou substituída.</small>' : ''}${historico ? `<small>${escapeHtml(data)} · ${a.lido ? 'Lido' : 'Não lido'}</small>` : ''}${!a.lido ? `<button type="button" class="botao secundario" data-ler-alerta="${Number(a.id)}">Marcar como lida</button>` : ''}</article>`;
    }
    async function carregar(atualizar = false, acrescentar = false, somenteCards = false) {
        if (ocupado) return;
        ocupado = true;
        if (verificar) verificar.disabled = true;
        if (mais) mais.disabled = true;
        try {
            const proxima = acrescentar ? pagina + 1 : 0;
            const dados = atualizar ? await apiService.post('/alertas/verificar', {}) : await apiService.get(`/alertas?pagina=${proxima}`);
            if (painel) painel.hidden = dados.novos.length === 0;
            cards.querySelector('.alerta-falha')?.remove();
            if (!somenteCards) pagina = proxima;
            const conteudoCards = dados.novos.map(a => registro(a)).join('');
            if (cards.dataset.conteudo !== conteudoCards) {
                cards.innerHTML = conteudoCards;
                cards.dataset.conteudo = conteudoCards;
            }
            if (lista && (!somenteCards || pagina === 0)) {
                if (!acrescentar) { lista.innerHTML = ''; carregados = 0; }
                lista.insertAdjacentHTML('beforeend', dados.itens.map(a => registro(a, true)).join(''));
                carregados += dados.itens.length;
                if (!carregados) lista.textContent = 'Nenhuma notificação lida. As pendentes aparecem no início da página.';
                mais.hidden = carregados >= dados.total;
            }
            if (estado) estado.textContent = dados.novos.length ? `${dados.novos.length} notificação(ões) não lida(s).` : 'Nenhuma notificação não lida.';
        } catch (erro) {
            if (estado) estado.textContent = erro.message || 'Não foi possível consultar as notificações.';
        } finally {
            ocupado = false;
            if (verificar) verificar.disabled = false;
            if (mais) mais.disabled = false;
        }
    }
    async function marcar(evento) {
        const botao = evento.target.closest('[data-ler-alerta]');
        if (!botao || botao.disabled || ocupado || leituraEmAndamento) return;
        leituraEmAndamento = true;
        botao.disabled = true;
        if (verificar) verificar.disabled = true;
        if (mais) mais.disabled = true;
        try {
            await apiService.post('/alertas/lidos', [Number(botao.dataset.lerAlerta)]);
            // A confirmação já foi persistida; retire o cartão mesmo se a próxima leitura falhar.
            botao.closest('.alerta-registro')?.remove();
            delete cards.dataset.conteudo;
            await carregar();
        } catch (erro) {
            botao.disabled = false;
            let aviso = botao.parentElement.querySelector('.alerta-erro');
            if (!aviso) { aviso = document.createElement('p'); aviso.className = 'alerta-erro'; botao.parentElement.append(aviso); }
            aviso.textContent = erro.message || 'Não foi possível marcar como lida. Tente novamente.';
        } finally {
            leituraEmAndamento = false;
            if (verificar) verificar.disabled = false;
            if (mais) mais.disabled = false;
        }
    }
    cards.addEventListener('click', marcar);
    lista?.addEventListener('click', marcar);
    verificar?.addEventListener('click', () => carregar(true));
    mais?.addEventListener('click', () => carregar(false, true));
    // A leitura permanece no servidor por usuário; cada entrada recupera as pendências.
    // Histórico já gravado deve aparecer mesmo se a verificação de novos alertas falhar.
    carregar().then(() => carregar(true));
    // Revalida estoque e validade sem exigir recarregar a tela e sem fechar o histórico aberto.
    const atualizarPendencias = () => {
        if (!document.hidden && estaAutenticado() && !leituraEmAndamento) carregar(true, false, true);
    };
    window.setInterval(atualizarPendencias, 30000);
    document.addEventListener('visibilitychange', atualizarPendencias);
    window.addEventListener('focus', atualizarPendencias);
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar, { once:true });
else iniciar();
