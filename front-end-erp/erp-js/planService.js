/**
 * planService.js
 * ---------------------------------------------------------------------------
 * Controle de acesso por plano no front-end.
 *
 * PLANOS:
 *   BASE  -> recursos básicos
 *   PLUS  -> recursos básicos + categorias/filtros + faturamento + IA
 *   PREMIUM -> tudo do PLUS
 *
 * IMPORTANTE:
 * Este controle melhora a experiência no navegador, mas NÃO substitui
 * autorização no Spring Boot. O backend deve validar o plano da empresa
 * antes de liberar endpoints pagos.
 * ---------------------------------------------------------------------------
 */

import { getUsuarioLogado } from './authService.js';

const PESO_PLANO = {
    BASE: 1,
    PLUS: 2,
    PREMIUM: 3,
};

function normalizarPlano(plano) {
    const valor = String(plano || 'BASE').toUpperCase();

    // Compatibilidade com nomes antigos/legados.
    if (valor === 'GRATUITO' || valor === 'BASICO' || valor === 'BÁSICO') return 'BASE';
    if (valor === 'INTERMEDIARIO' || valor === 'INTERMEDIÁRIO' || valor === 'EMPRESARIAL') return 'PLUS';
    if (valor === 'CORPORATIVO') return 'PREMIUM';

    return PESO_PLANO[valor] ? valor : 'BASE';
}

export function getPlanoAtual() {
    return normalizarPlano(getUsuarioLogado()?.plano);
}

export function temAcesso(planoMinimo = 'BASE') {
    return PESO_PLANO[getPlanoAtual()] >= PESO_PLANO[normalizarPlano(planoMinimo)];
}

export function nomePlano(plano) {
    const nomes = {
        BASE: 'Plano Básico',
        PLUS: 'Plano Intermediário',
        PREMIUM: 'Plano Premium',
    };
    return nomes[normalizarPlano(plano)] || 'Plano Básico';
}

/**
 * Aplica bloqueios visuais aos elementos marcados com data-plano-minimo.
 * O bloqueio é apenas de interface. A autorização real deverá ser feita
 * pelo Spring Security/backend.
 */
export function aplicarControlePlanos() {
    document.querySelectorAll('[data-plano-minimo]').forEach((elemento) => {
        const minimo = elemento.dataset.planoMinimo;

        if (temAcesso(minimo)) {
            elemento.classList.remove('recurso-bloqueado');
            elemento.removeAttribute('aria-disabled');
            elemento.removeAttribute('title');
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

        if (elemento.matches('input, select, textarea, button')) {
            elemento.disabled = true;
        }

        if (elemento.tagName === 'A') {
            elemento.addEventListener('click', (evento) => {
                evento.preventDefault();
                window.alert(`Este recurso está disponível no ${nomePlano(minimo)}.`);
            });
        }
    });
}
