/** Datas e horários do ERP sempre usam o fuso comercial de Fortaleza. */
export const FUSO_ERP = 'America/Fortaleza';
const formatadorData = new Intl.DateTimeFormat('pt-BR', { timeZone: FUSO_ERP });
const formatadorDataHora = new Intl.DateTimeFormat('pt-BR', {
    timeZone: FUSO_ERP, dateStyle: 'short', timeStyle: 'short', hourCycle: 'h23',
});
const formatadorHora = new Intl.DateTimeFormat('pt-BR', {
    timeZone: FUSO_ERP, hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
});
const formatadorDiaComercial = new Intl.DateTimeFormat('en', {
    timeZone: FUSO_ERP, year: 'numeric', month: '2-digit', day: '2-digit',
});

function dataValida(valor) {
    if (valor instanceof Date) return Number.isNaN(valor.getTime()) ? null : valor;
    if (valor == null || valor === '') return null;
    const data = new Date(valor);
    return Number.isNaN(data.getTime()) ? null : data;
}

export function formatarData(valor, alternativa = '—') {
    if (typeof valor === 'string') {
        const apenasData = valor.trim().match(/^(\d{4})-(\d{2})-(\d{2})$/);
        if (apenasData) return `${apenasData[3]}/${apenasData[2]}/${apenasData[1]}`;
    }
    const data = dataValida(valor);
    return data
        ? formatadorData.format(data)
        : alternativa;
}

export function formatarDataHora(valor, alternativa = '—') {
    const data = dataValida(valor);
    return data
        ? formatadorDataHora.format(data)
        : alternativa;
}

export function formatarHora(valor = new Date(), alternativa = '—') {
    const data = dataValida(valor);
    return data
        ? formatadorHora.format(data)
        : alternativa;
}

export function hojeNoFusoERP() {
    const partes = formatadorDiaComercial.formatToParts(new Date());
    const valor = Object.fromEntries(partes.map(({ type, value }) => [type, value]));
    return `${valor.year}-${valor.month}-${valor.day}`;
}
