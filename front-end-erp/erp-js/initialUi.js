// Define a aparência inicial antes que o navegador desenhe a página.
(() => {
    let plano = 'BASE';
    try {
        const usuario = JSON.parse(sessionStorage.getItem('usuario') || 'null');
        const valor = String(usuario?.plano || 'BASE').trim().toUpperCase();
        if (['PLUS', 'INTERMEDIARIO', 'INTERMEDIÁRIO', 'EMPRESARIAL'].includes(valor)) plano = 'PLUS';
        if (['PREMIUM', 'CORPORATIVO'].includes(valor)) plano = 'PREMIUM';
    } catch (_) {
        // A verificação de acesso continua no app.js e no backend.
    }
    document.documentElement.dataset.planoInicial = plano;
})();
