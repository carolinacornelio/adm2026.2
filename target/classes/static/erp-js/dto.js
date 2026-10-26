/**
 * dto.js
 * ---------------------------------------------------------------------------
 * DTOs (Data Transfer Objects)
 * ---------------------------------------------------------------------------
 * Um DTO é apenas o "formato combinado" do JSON que trafega entre front-end
 * e back-end. Aqui centralizamos:
 *
 *   1) Funções "to...DTO"   -> transformam os dados do formulário HTML no
 *      objeto que será enviado (JSON.stringify) para a API Spring.
 *   2) Funções "from...DTO" -> transformam o JSON que a API devolve em um
 *      formato conveniente para exibir na tela.
 *
 * Manter isso em um único lugar evita que o "formato dos dados" fique
 * espalhado (e divergente) entre vários arquivos, e facilita ajustar o
 * mapeamento quando os nomes de campo do Spring forem definidos oficialmente.
 * ---------------------------------------------------------------------------
 */

// ---------------------------------------------------------------------------
// Autenticação — corresponde a algo como UsuarioCadastroDTO / LoginDTO no Spring
// ---------------------------------------------------------------------------

/** Formulário de cadastro -> DTO enviado para POST /api/auth/cadastro */
export function toUsuarioCadastroDTO(formData) {
    return {
        nome: formData.nome.trim(),
        controlaValidade: formData.controlaValidade === 'true',
        empresa: formData.empresa.trim(),
        email: formData.email.trim().toLowerCase(),
        senha: formData.senha,
        plano: formData.plano || 'BASE',
        perguntaSeguranca1: formData.perguntaSeguranca1,
        respostaSeguranca1: formData.respostaSeguranca1.trim(),
        perguntaSeguranca2: formData.perguntaSeguranca2,
        respostaSeguranca2: formData.respostaSeguranca2.trim(),
        perguntaSeguranca3: formData.perguntaSeguranca3,
        respostaSeguranca3: formData.respostaSeguranca3.trim(),
        // "confirmarSenha" e "terms" são validados só no front-end;
        // não fazem parte do DTO que vai para o backend.
    };
}

/** Formulário de login -> DTO enviado para POST /api/auth/login */
export function toUsuarioLoginDTO(formData) {
    return {
        email: formData.email.trim().toLowerCase(),
        senha: formData.senha,
    };
}

// ---------------------------------------------------------------------------
// Pessoas (clientes/fornecedores) — corresponde a PessoaDTO no Spring
// ---------------------------------------------------------------------------

/** Converte o texto do <select> em um valor de enum, como o Spring espera. */
function tipoPessoaParaEnum(tipoTexto) {
    const mapa = {
        Cliente: 'CLIENTE',
        Fornecedor: 'FORNECEDOR',
        'Cliente e Fornecedor': 'CLIENTE_FORNECEDOR',
    };
    return mapa[tipoTexto] || 'CLIENTE';
}

/** Converte o enum de volta em texto legível para exibir na listagem. */
function tipoPessoaParaTexto(tipoEnum) {
    const mapa = {
        CLIENTE: 'Cliente',
        FORNECEDOR: 'Fornecedor',
        CLIENTE_FORNECEDOR: 'Cliente e Fornecedor',
    };
    return mapa[tipoEnum] || tipoEnum;
}

/** Formulário de cadastro de pessoa -> DTO enviado para POST/PUT /api/pessoas */
export function toPessoaDTO(formData) {
    return {
        nome: formData.nome.trim(),
        controlaValidade: formData.controlaValidade === 'true',
        documento: formData.documento.trim(),
        telefone: formData.telefone.trim(),
        email: formData.email.trim(),
        endereco: formData.endereco ? formData.endereco.trim() : '',
        tipo: tipoPessoaParaEnum(formData.tipo),
        banco: formData.banco ? formData.banco.trim() : '',
        agencia: formData.agencia ? formData.agencia.trim() : '',
        conta: formData.conta ? formData.conta.trim() : '',
        chavePix: formData.chavePix ? formData.chavePix.trim() : '',
        contratoSocialUrl: formData.contratoSocialUrl ? formData.contratoSocialUrl.trim() : '',
    };
}

/** Registro vindo do backend/localStorage -> formato usado na listagem. */
export function fromPessoaDTO(pessoa) {
    return {
        id: pessoa.id,
        nome: pessoa.nome,
        documento: pessoa.documento,
        telefone: pessoa.telefone,
        email: pessoa.email,
        endereco: pessoa.endereco,
        tipo: pessoa.tipo || 'CLIENTE',
        tipoTexto: tipoPessoaParaTexto(pessoa.tipo),
    };
}

// ---------------------------------------------------------------------------
// Produtos — corresponde a ProdutoDTO no Spring
// ---------------------------------------------------------------------------

/** Formulário de cadastro de produto -> DTO enviado para POST/PUT /api/produtos */
export function toProdutoDTO(formData) {
    return {
        nome: formData.nome.trim(),
        controlaValidade: formData.controlaValidade === 'true',
        codigo: formData.codigo.trim(),
        descricao: (formData.descricao || '').trim(),
        precoCompra: Number(formData.precoCompra) || 0,
        precoVenda: Number(formData.precoVenda) || 0,
        estoqueMinimo: Number(formData.estoqueMinimo) || 0,
        categoriaId: formData.categoriaId ? Number(formData.categoriaId) : null,
        // Todo produto novo nasce com estoque zerado; compras futuras
        // (Etapa seguinte) é que vão incrementar este valor no backend.
        quantidadeEstoque: 0,
    };
}

/** Registro vindo do backend/localStorage -> formato usado na listagem/estoque. */
export function fromProdutoDTO(produto) {
    const quantidadeEstoque = Math.max(0, Number(produto.quantidadeEstoque) || 0);
    const estoqueMinimo = Math.max(0, Number(produto.estoqueMinimo) || 0);
    const limiteProximo = estoqueMinimo + Math.max(1, Math.ceil(estoqueMinimo * 0.2));

    let situacao = 'OK';
    if (quantidadeEstoque <= 0) situacao = 'Esgotado';
    else if (quantidadeEstoque < estoqueMinimo) situacao = 'Abaixo do mínimo';
    else if (quantidadeEstoque === estoqueMinimo) situacao = 'No estoque mínimo';
    else if (quantidadeEstoque <= limiteProximo) situacao = 'Próximo do mínimo';
    if (['OK', 'Esgotado', 'No estoque mínimo', 'Abaixo do mínimo', 'Próximo do mínimo'].includes(produto.situacaoEstoque)) {
        situacao = produto.situacaoEstoque;
    }

    return {
        id: produto.id,
        controlaValidade: Boolean(produto.controlaValidade),
        nome: produto.nome,
        codigo: produto.codigo,
        descricao: produto.descricao,
        precoCompra: produto.precoCompra,
        precoVenda: produto.precoVenda,
        estoqueMinimo,
        quantidadeEstoque,
        quantidadeTotal: Number(produto.quantidadeTotal ?? quantidadeEstoque),
        situacaoValidade: produto.situacaoValidade ?? 'Não informada',
        nivelValidade: produto.nivelValidade ?? '',
        unidadesVencidas: Number(produto.unidadesVencidas) || 0,
        unidadesValidadePendente: Number(produto.unidadesValidadePendente) || 0,
        proximaValidade: produto.proximaValidade ?? null,
        situacao,
        categoriaId: produto.categoriaId ?? null,
        categoriaNome: produto.categoriaNome ?? produto.categoria?.nome ?? '',
    };
}

// ---------------------------------------------------------------------------
// Itens de Compra/Venda — corresponde a ItemPedidoDTO no Spring
// ---------------------------------------------------------------------------

/** Uma linha da tabela de itens (compra ou venda) -> DTO do item. */
function toItemPedidoDTO(item) {
    return {
        // Valores de <select> chegam como texto; a API espera um ID numérico.
        produtoId: Number(item.produtoId),
        quantidade: Number(item.quantidade),
        precoUnitario: Number(item.precoUnitario),
        lote: item.lote || '',
        validade: item.validade || null,
    };
}

/** Soma quantidade × preço de cada item — usado como total do pedido. */
function calcularTotalItens(itensDTO) {
    return itensDTO.reduce((soma, item) => soma + item.quantidade * item.precoUnitario, 0);
}

// ---------------------------------------------------------------------------
// Compras — corresponde a CompraDTO no Spring
// ---------------------------------------------------------------------------

/** Dados lidos da tela de compras -> DTO enviado para POST /api/compras */
export function toCompraDTO({ fornecedorId, data, itens, tipoDocumento, notaFiscal, contratoId }) {
    const itensDTO = itens.map(toItemPedidoDTO);
    return {
        fornecedorId: Number(fornecedorId),
        data,
        itens: itensDTO,
        total: calcularTotalItens(itensDTO),
        tipoDocumento: tipoDocumento || 'MERCADORIA',
        notaFiscal: notaFiscal || '',
        contratoId: contratoId ? Number(contratoId) : null,
    };
}

// ---------------------------------------------------------------------------
// Vendas — corresponde a VendaDTO no Spring
// ---------------------------------------------------------------------------

/** Dados lidos da tela de vendas -> DTO enviado para POST /api/vendas */
export function toVendaDTO({ clienteId, data, itens }) {
    const itensDTO = itens.map(toItemPedidoDTO);
    return {
        clienteId: Number(clienteId),
        data,
        itens: itensDTO,
        total: calcularTotalItens(itensDTO),
    };
}

// ---------------------------------------------------------------------------
// Dashboard — corresponde a DashboardIndicadoresDTO no Spring
// ---------------------------------------------------------------------------

/** Resposta de GET /api/dashboard/indicadores -> objeto usado pelas telas. */
export function fromDashboardIndicadoresDTO(dados) {
    return {
        totalPessoas: dados.totalPessoas ?? 0,
        totalProdutos: dados.totalProdutos ?? 0,
        totalCompras: dados.totalCompras ?? 0,
        totalVendas: dados.totalVendas ?? 0,
    };
}
