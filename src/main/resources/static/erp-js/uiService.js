/**
 * uiService.js
 * ---------------------------------------------------------------------------
 * CAMADA DE INTERFACE (DOM / estados de carregamento e erro)
 * ---------------------------------------------------------------------------
 * Nenhuma regra de negócio deve morar aqui — só manipulação de tela:
 * ligar/desligar "carregando..." em um botão, mostrar mensagens de erro,
 * transformar um <form> em objeto JS, e desenhar linhas de tabela.
 *
 * Isso mantém os "controllers" de cada página (auth.js, pessoas.js...)
 * curtos e fáceis de ler: eles decidem O QUE fazer, e este arquivo decide
 * COMO isso aparece na tela.
 * ---------------------------------------------------------------------------
 */

/** Lê todos os campos de um <form> (por atributo name) e devolve um objeto. */
export function formParaObjeto(formElement) {
    const formData = new FormData(formElement);
    const objeto = {};
    formData.forEach((valor, chave) => {
        objeto[chave] = valor;
    });
    return objeto;
}

/**
 * Coloca um botão em estado de "carregando": desabilita o clique duplo e
 * troca o texto por um indicador visual, guardando o texto original para
 * poder restaurar depois com `pararCarregando`.
 */
export function iniciarCarregando(botaoElement, textoCarregando = 'Enviando...') {
    if (!botaoElement) return;
    botaoElement.dataset.textoOriginal = botaoElement.textContent;
    botaoElement.textContent = textoCarregando;
    botaoElement.disabled = true;
}

export function pararCarregando(botaoElement) {
    if (!botaoElement) return;
    botaoElement.textContent = botaoElement.dataset.textoOriginal || botaoElement.textContent;
    botaoElement.disabled = false;
}

/**
 * Garante que exista uma <div> de mensagem logo após o elemento de
 * referência (normalmente o próprio <form>), criando-a na primeira vez que
 * for necessária. Assim não precisamos editar todos os HTMLs à mão.
 */
function getOuCriarCaixaMensagem(elementoReferencia) {
    let caixa = elementoReferencia.nextElementSibling;
    if (!caixa || !caixa.classList.contains('mensagem-formulario')) {
        caixa = document.createElement('div');
        caixa.className = 'mensagem-formulario';
        elementoReferencia.insertAdjacentElement('afterend', caixa);
    }
    return caixa;
}

/**
 * Exibe uma mensagem de erro (ex: vinda de um ApiError) logo abaixo do
 * formulário. A cor vem do CSS: `.mensagem-formulario` já é vermelha por
 * padrão (ver tela.css) — por isso não precisamos definir estilo aqui.
 */
export function mostrarErro(elementoReferencia, mensagem) {
    const caixa = getOuCriarCaixaMensagem(elementoReferencia);
    caixa.textContent = mensagem;
    caixa.removeAttribute('data-tipo');
}

/**
 * Exibe uma mensagem de sucesso logo abaixo do formulário. O atributo
 * `data-tipo="sucesso"` ativa a variante verde já definida em tela.css:
 * `.mensagem-formulario[data-tipo="sucesso"]`.
 */
export function mostrarSucesso(elementoReferencia, mensagem) {
    const caixa = getOuCriarCaixaMensagem(elementoReferencia);
    caixa.textContent = mensagem;
    caixa.dataset.tipo = 'sucesso';
}

/** Remove qualquer mensagem de erro/sucesso exibida anteriormente. */
export function limparMensagem(elementoReferencia) {
    const caixa = elementoReferencia.nextElementSibling;
    if (caixa && caixa.classList.contains('mensagem-formulario')) {
        caixa.textContent = '';
        caixa.removeAttribute('data-tipo');
    }
}

/**
 * Traduz o campo `situacao` calculado em dto.js (OK/Baixo/Esgotado) para a
 * classe CSS de badge já pronta em tela.css (.status.normal/.baixo/.sem-estoque).
 */
export function classeStatusEstoque(situacao) {
    const mapa = { OK: 'normal', Baixo: 'baixo', Esgotado: 'sem-estoque' };
    return mapa[situacao] || 'normal';
}

/**
 * SEGURANÇA: escapa caracteres especiais de HTML antes de inserir um valor
 * dentro de uma string que será usada em innerHTML/insertAdjacentHTML.
 *
 * Por quê isso é necessário: `nome`, `telefone`, `endereco` etc. são campos
 * digitados pelo próprio usuário no formulário. Se alguém cadastrar uma
 * pessoa com o "nome" `<img src=x onerror="alert(1)">` e nós colocarmos esse
 * texto direto dentro de innerHTML (ex: `<td>${pessoa.nome}</td>`), o
 * navegador vai interpretar isso como HTML de verdade e EXECUTAR o script —
 * um ataque conhecido como XSS (Cross-Site Scripting) armazenado, porque o
 * dado malicioso fica salvo e afeta qualquer pessoa que abrir a listagem.
 *
 * Convertendo `<` em `&lt;`, `>` em `&gt;` etc., o navegador exibe o texto
 * exatamente como foi digitado, sem executá-lo como código.
 *
 * USE ESTA FUNÇÃO em todo lugar onde um dado vindo de um formulário
 * (nome, telefone, e-mail, descrição...) for inserido via innerHTML.
 */
export function escapeHtml(valor) {
    return String(valor ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

/**
 * Renderiza as linhas de uma tabela a partir de uma lista de itens.
 * `criarLinhaHtml` é uma função (item) => string HTML da <tr>, definida por
 * quem chama, já que cada tabela (pessoas, produtos, estoque) tem colunas
 * diferentes.
 */
export function renderizarTabela(tbodyElement, itens, criarLinhaHtml, mensagemVazio) {
    if (!tbodyElement) return;

    if (!itens || itens.length === 0) {
        const colunas = tbodyElement.closest('table')?.querySelectorAll('thead th').length || 1;
        tbodyElement.innerHTML = `<tr><td colspan="${colunas}" class="estado-vazio">${mensagemVazio}</td></tr>`;
        return;
    }

    tbodyElement.innerHTML = itens.map(criarLinhaHtml).join('');
}

/**
 * Trata um erro de forma padronizada nas páginas: mostra a mensagem amigável
 * (ApiError já vem com uma pronta) e loga o detalhe técnico no console para
 * quem estiver depurando.
 */
export function tratarErro(elementoReferencia, erro) {
    console.error(erro);
    const mensagem = erro?.message || 'Ocorreu um erro inesperado. Tente novamente.';
    mostrarErro(elementoReferencia, mensagem);
}

/**
 * Exibe uma notificação flutuante (toast) no canto superior direito da tela.
 */
function exibirNotificacao(mensagem, tipo = 'sucesso') {
    if (typeof document === 'undefined') return;

    let container = document.getElementById('container-notificacoes-erp');
    if (!container) {
        container = document.createElement('div');
        container.id = 'container-notificacoes-erp';
        Object.assign(container.style, {
            position: 'fixed',
            top: '20px',
            right: '20px',
            zIndex: '9999',
            display: 'flex',
            flexDirection: 'column',
            gap: '10px',
            maxWidth: '420px',
        });
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    const ehSucesso = tipo === 'sucesso';
    Object.assign(toast.style, {
        padding: '12px 16px',
        borderRadius: '8px',
        fontSize: '13px',
        fontWeight: '600',
        boxShadow: '0 4px 12px rgba(16, 24, 40, 0.15)',
        background: ehSucesso ? '#dcfae6' : '#fee4e2',
        color: ehSucesso ? '#087443' : '#b42318',
        border: `1px solid ${ehSucesso ? '#a6f4c5' : '#fecdca'}`,
        transition: 'opacity 0.3s ease',
    });
    toast.textContent = mensagem;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        setTimeout(() => toast.remove(), 300);
    }, 4500);
}

export function notificarSucesso(mensagem) {
    exibirNotificacao(mensagem, 'sucesso');
}

export function notificarErro(mensagem) {
    exibirNotificacao(mensagem, 'erro');
}
