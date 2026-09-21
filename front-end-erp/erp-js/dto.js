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
        empresa: formData.empresa.trim(),
        email: formData.email.trim().toLowerCase(),
        senha: formData.senha,
        plano: formData.plano || 'BASE',
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
        documento: formData.documento.trim(),
        telefone: formData.telefone.trim(),
        email: formData.email.trim(),
        endereco: formData.endereco.trim(),
        tipo: tipoPessoaParaEnum(formData.tipo),
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
    const quantidadeEstoque = produto.quantidadeEstoque ?? 0;
    const estoqueMinimo = produto.estoqueMinimo ?? 0;

    let situacao = 'OK';
    if (quantidadeEstoque <= 0) situacao = 'Esgotado';
    else if (quantidadeEstoque <= estoqueMinimo) situacao = 'Baixo';

    return {
        id: produto.id,
        nome: produto.nome,
        codigo: produto.codigo,
        descricao: produto.descricao,
        precoCompra: produto.precoCompra,
        precoVenda: produto.precoVenda,
        estoqueMinimo,
        quantidadeEstoque,
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
export function toCompraDTO({ fornecedorId, data, itens }) {
    const itensDTO = itens.map(toItemPedidoDTO);
    return {
        fornecedorId: Number(fornecedorId),
        data,
        itens: itensDTO,
        total: calcularTotalItens(itensDTO),
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
