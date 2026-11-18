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
 * Aplica as mesmas cores de estoque em cadastros e operação.
 */
export function classeStatusEstoque(situacao) {
    const mapa = { OK: 'normal', Baixo: 'estoque-minimo', 'Abaixo do mínimo': 'estoque-minimo', 'No estoque mínimo': 'estoque-minimo', 'Próximo do mínimo': 'baixo', Esgotado: 'estoque-esgotado' };
    return mapa[situacao] || 'status-neutro';
}

export function classeStatusValidade(nivel) {
    return { BLOQUEADO: 'validade-bloqueada', PRIORIDADE: 'validade-prioridade', IMPORTANTE: 'validade-importante', NORMAL: 'baixo', REGULAR: 'normal' }[nivel] || 'status-neutro';
}

export function textoStatusValidade(situacao, nivel) {
    const prioridade = { PRIORIDADE: 'Prioridade', IMPORTANTE: 'Importante', NORMAL: 'Aviso' }[nivel];
    return situacao === 'A vencer' && prioridade ? `${situacao} · ${prioridade}` : (situacao || 'Não informada');
}

export function badgeValidade(produto) {
    const detalhes = [];
    if (Number(produto.unidadesVencidas) > 0) detalhes.push(`${produto.unidadesVencidas} unidade(s) vencida(s)`);
    if (Number(produto.unidadesValidadePendente) > 0) detalhes.push(`${produto.unidadesValidadePendente} unidade(s) com validade pendente`);
    if (produto.proximaValidade) {
        const data = String(produto.proximaValidade).slice(0,10).split('-').reverse().join('/');
        detalhes.push(`Próxima validade: ${data}`);
    }
    return `<span class="status ${classeStatusValidade(produto.nivelValidade)}">${escapeHtml(textoStatusValidade(produto.situacaoValidade, produto.nivelValidade))}</span>${detalhes.length ? `<small class="status-detalhe">${escapeHtml(detalhes.join(' · '))}</small>` : ''}`;
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
