/** Regras de plano no frontend; a autorização real continua no backend. */
import { getUsuarioLogado } from './authService.js';

const PESO_PLANO = { BASE: 1, PLUS: 2, PREMIUM: 3 };

export function normalizarPlano(plano) {
    const valor = String(plano || 'BASE').trim().toUpperCase();
    if (['INTERMEDIARIO', 'INTERMEDIÁRIO', 'EMPRESARIAL'].includes(valor)) return 'PLUS';
    if (['PREMIUM', 'CORPORATIVO'].includes(valor)) return 'PREMIUM';
    return 'BASE';
}

export function getPlanoAtual() {
    return normalizarPlano(getUsuarioLogado()?.plano);
}

export function temAcesso(planoMinimo = 'BASE') {
    return PESO_PLANO[getPlanoAtual()] >= PESO_PLANO[normalizarPlano(planoMinimo)];
}

export function nomePlano(plano) {
    return {
        BASE: 'Plano Básico',
        PLUS: 'Plano Intermediário',
        PREMIUM: 'Plano Premium'
    }[normalizarPlano(plano)];
}

/** Oculta ou desabilita elementos marcados com data-plano-minimo. */
export function aplicarControlePlanos() {
    document.querySelectorAll('[data-plano-minimo]').forEach((elemento) => {
        const minimo = elemento.dataset.planoMinimo;

        if (temAcesso(minimo)) {
            elemento.classList.remove('recurso-bloqueado');
            elemento.removeAttribute('aria-disabled');
            if (elemento.dataset.planoOcultar === 'true') elemento.hidden = false;
            if (elemento.classList.contains('recurso-aviso')) elemento.hidden = true;
            return;
        }

        if (elemento.dataset.planoOcultar === 'true') {
            elemento.hidden = true;
            return;
        }

        elemento.classList.add('recurso-bloqueado');
        elemento.setAttribute('aria-disabled', 'true');
        elemento.title = `${nomePlano(minimo)} necessário.`;

        if (elemento.matches('input,select,textarea,button')) elemento.disabled = true;
        if (elemento.tagName === 'A') {
            elemento.addEventListener('click', (evento) => {
                evento.preventDefault();
                window.alert(`Este recurso está disponível no ${nomePlano(minimo)}.`);
            });
        }
    });
}
