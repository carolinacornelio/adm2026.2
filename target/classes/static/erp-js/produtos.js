/** Controller das telas de cadastro e listagem de produtos, incluindo categorias. */
import { listarProdutos, criarProduto, removerProduto } from './produtosService.js';
import { listarCategorias, criarCategoria } from './categoriasService.js';
import { estaAutenticado } from './authService.js';
import { toProdutoDTO, fromProdutoDTO } from './dto.js';
import {
    formParaObjeto,
    iniciarCarregando,
    pararCarregando,
    mostrarErro,
    mostrarSucesso,
    limparMensagem,
    renderizarTabela,
    tratarErro,
    classeStatusEstoque,
    badgeValidade,
    escapeHtml,
    notificarSucesso,
} from './uiService.js';

/** Chave usada para avisar a listagem de que um produto acabou de ser cadastrado. */
const CHAVE_PRODUTO_CADASTRADO = 'produtoRecemCadastrado';
const ATRASO_REDIRECIONAMENTO_MS = 900;

let categoriasDisponiveis = [];
let ultimaListagem = 0;

function validarProduto(dados) {
    if (!dados.nome?.trim()) return 'Informe o nome do produto.';
    if (!dados.codigo?.trim()) return 'Informe o código do produto.';
    if (dados.precoCompra === '' || Number(dados.precoCompra) < 0) return 'Informe um preço de compra válido.';
    if (dados.precoVenda === '' || Number(dados.precoVenda) < 0) return 'Informe um preço de venda válido.';
    if (dados.estoqueMinimo === '' || !Number.isInteger(Number(dados.estoqueMinimo)) || Number(dados.estoqueMinimo) < 0) return 'Informe um estoque mínimo inteiro e não negativo.';
    return null;
}

function preencherSelectCategorias(selectElement, permitirTodas = false) {
    if (!selectElement) return;
    const primeiraOpcao = permitirTodas ? 'Todas as categorias' : 'Sem categoria';
    selectElement.innerHTML = `<option value="">${primeiraOpcao}</option>` + categoriasDisponiveis
        .map((categoria) => `<option value="${escapeHtml(categoria.id)}">${escapeHtml(categoria.nome)}</option>`)
        .join('');
}

async function carregarCategorias(selectElement, permitirTodas = false) {
    if (!selectElement) return;
    try {
        categoriasDisponiveis = await listarCategorias();
        preencherSelectCategorias(selectElement, permitirTodas);
    } catch (erro) {
        console.error('Não foi possível carregar categorias:', erro);
        selectElement.innerHTML = '<option value="">Plano Intermediário necessário</option>';
    }
}

function configurarNovaCategoria(formProduto, selectCategoria) {
    const abrir = document.getElementById('mostrarNovaCategoria');
    const bloco = document.getElementById('novaCategoria');
    const nome = document.getElementById('nomeNovaCategoria');
    const salvar = document.getElementById('salvarNovaCategoria');
    if (!abrir || !bloco || !nome || !salvar) return;

    abrir.addEventListener('click', () => {
        bloco.hidden = false;
        nome.focus();
    });

    salvar.addEventListener('click', async () => {
        const valor = nome.value.trim();
        if (!valor) {
            mostrarErro(formProduto, 'Informe o nome da nova categoria.');
            return;
        }

        iniciarCarregando(salvar, 'Salvando...');
        try {
            const criada = await criarCategoria(valor);
            await carregarCategorias(selectCategoria);
            selectCategoria.value = String(criada.id);
            nome.value = '';
            bloco.hidden = true;
            limparMensagem(formProduto);
        } catch (erro) {
            tratarErro(formProduto, erro);
        } finally {
            pararCarregando(salvar);
        }
    });
}

function configurarFormularioCadastro(formProduto) {
    const botaoCadastrar = formProduto.querySelector('button.primario');
    const selectCategoria = formProduto.querySelector('[name="categoriaId"]');
    carregarCategorias(selectCategoria);
    configurarNovaCategoria(formProduto, selectCategoria);

    formProduto.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        limparMensagem(formProduto);
        const dados = formParaObjeto(formProduto);
        const erroValidacao = validarProduto(dados);
        if (erroValidacao) {
            mostrarErro(formProduto, erroValidacao);
            return;
        }

        iniciarCarregando(botaoCadastrar, 'Cadastrando...');
        let criado;
        try {
            criado = await criarProduto(toProdutoDTO(dados));
        } catch (erro) {
            tratarErro(formProduto, erro);
            pararCarregando(botaoCadastrar);
            return;
        }

        // Confirmação visual: mensagem no formulário e, na listagem, aviso + destaque da linha.
        const nome = dados.nome.trim();
        try {
            sessionStorage.setItem(CHAVE_PRODUTO_CADASTRADO, JSON.stringify({
                id: criado?.id ?? null,
                codigo: dados.codigo.trim(),
                nome,
            }));
        } catch { /* sem sessionStorage a listagem só não mostra o aviso */ }
        mostrarSucesso(formProduto, `Produto "${nome}" cadastrado com sucesso! Abrindo a lista de produtos...`);
        botaoCadastrar.textContent = 'Cadastrado ✓';
        window.setTimeout(() => { window.location.href = 'produtos-listagem.html'; }, ATRASO_REDIRECIONAMENTO_MS);
    });
}

function formatarMoeda(valor) {
    return Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function criarLinhaProduto(produto) {
    return `
        <tr data-produto-id="${escapeHtml(produto.id)}" data-produto-codigo="${escapeHtml(produto.codigo)}">
            <td>${escapeHtml(produto.codigo)}</td>
            <td>${escapeHtml(produto.nome)}</td>
            <td>${escapeHtml(produto.categoriaNome || 'Sem categoria')}</td>
            <td>${formatarMoeda(produto.precoVenda)}</td>
            <td title="Saldo vendável; o saldo físico pode incluir mercadoria bloqueada">${escapeHtml(produto.quantidadeEstoque)}<small class="status-detalhe">Físico: ${escapeHtml(produto.quantidadeTotal)} · Mínimo: ${escapeHtml(produto.estoqueMinimo)}</small></td>
            <td><span class="status ${classeStatusEstoque(produto.situacao)}">${escapeHtml(produto.situacao)}</span></td>
            <td>${badgeValidade(produto)}</td>
            <td><div class="acoes-tabela"><button class="acao-mini excluir" data-excluir="${escapeHtml(produto.id)}" type="button">Excluir</button></div></td>
        </tr>`;
}

async function carregarListagemProdutos(tbody, termoBusca = '', categoriaId = '') {
    const consulta = ++ultimaListagem;
    try {
        const produtos = (await listarProdutos({ categoriaId })).map(fromProdutoDTO);
        if (consulta !== ultimaListagem) return;
        const termo = termoBusca.trim().toLowerCase();
        const filtrados = produtos.filter((produto) => {
            if (!termo) return true;
            return produto.nome.toLowerCase().includes(termo)
                || String(produto.codigo || '').toLowerCase().includes(termo)
                || String(produto.categoriaNome || '').toLowerCase().includes(termo);
        });
        renderizarTabela(tbody, filtrados, criarLinhaProduto, 'Nenhum produto encontrado com esses filtros.');
    } catch (erro) {
        if (consulta !== ultimaListagem) return;
        console.error(erro);
        tbody.innerHTML = `<tr><td colspan="8">${escapeHtml(erro.message || 'Não foi possível carregar a lista de produtos.')}</td></tr>`;
    }
}

function lerProdutoRecemCadastrado() {
    try {
        const valor = sessionStorage.getItem(CHAVE_PRODUTO_CADASTRADO);
        sessionStorage.removeItem(CHAVE_PRODUTO_CADASTRADO);
        return valor ? JSON.parse(valor) : null;
    } catch {
        return null;
    }
}

function destacarProduto(tbody, produto) {
    const linha = [...tbody.querySelectorAll('tr[data-produto-id]')].find((tr) =>
        (produto.id != null && tr.dataset.produtoId === String(produto.id))
        || tr.dataset.produtoCodigo === String(produto.codigo));
    if (!linha) return;
    linha.scrollIntoView({ block: 'center', behavior: 'smooth' });
    linha.style.transition = 'background-color 1.5s ease';
    linha.style.backgroundColor = '#dcfae6';
    window.setTimeout(() => { linha.style.backgroundColor = ''; }, 4000);
}

async function configurarListagem(tbody) {
    const campoPesquisa = document.getElementById('pesquisaProdutos');
    const filtroCategoria = document.getElementById('filtroCategoria');
    await carregarCategorias(filtroCategoria, true);
    const recarregar = () => carregarListagemProdutos(tbody, campoPesquisa?.value || '', filtroCategoria?.value || '');
    const recemCadastrado = lerProdutoRecemCadastrado();
    if (recemCadastrado) notificarSucesso(`Produto "${recemCadastrado.nome}" cadastrado com sucesso.`);
    await recarregar();
    if (recemCadastrado) destacarProduto(tbody, recemCadastrado);
    campoPesquisa?.addEventListener('input', recarregar);
    filtroCategoria?.addEventListener('change', recarregar);
    // A listagem não pode manter o saldo/status da abertura após uma venda em outra tela.
    let atualizando = false;
    const atualizarSaldos = async () => {
        if (atualizando || document.hidden) return;
        atualizando = true;
        try { await recarregar(); } finally { atualizando = false; }
    };
    window.setInterval(atualizarSaldos, 15000);
    window.addEventListener('focus', atualizarSaldos);
    document.addEventListener('visibilitychange', atualizarSaldos);

    tbody.addEventListener('click', async (evento) => {
        const botao = evento.target.closest('[data-excluir]');
        if (!botao || !window.confirm('Deseja realmente excluir este produto?')) return;
        try {
            await removerProduto(botao.dataset.excluir);
            await recarregar();
        } catch (erro) {
            window.alert(erro.message || 'Não foi possível excluir este produto.');
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;
    const formProduto = document.getElementById('formProduto');
    if (formProduto) configurarFormularioCadastro(formProduto);
    const listaProdutos = document.getElementById('listaProdutos');
    if (listaProdutos) configurarListagem(listaProdutos);
});
