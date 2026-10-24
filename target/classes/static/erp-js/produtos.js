/** Controller das telas de cadastro e listagem de produtos, incluindo categorias. */
import { listarProdutos, criarProduto, criarProdutosEmLote, removerProduto } from './produtosService.js';
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
    escapeHtml,
} from './uiService.js';

let categoriasDisponiveis = [];

function validarProduto(dados) {
    // Cadastro rápido: só nome e preço de venda são obrigatórios.
    // Código (o servidor gera se vier vazio), preço de compra e estoque mínimo são opcionais.
    if (!dados.nome?.trim()) return 'Informe o nome do produto.';
    if (dados.precoVenda === '' || Number(dados.precoVenda) < 0) return 'Informe um preço de venda válido.';
    if (dados.precoCompra !== '' && Number(dados.precoCompra) < 0) return 'O preço de compra não pode ser negativo.';
    if (dados.estoqueMinimo !== '' && (!Number.isInteger(Number(dados.estoqueMinimo)) || Number(dados.estoqueMinimo) < 0)) return 'Informe um estoque mínimo inteiro e não negativo.';
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
    const botaoCadastrarOutro = document.getElementById('cadastrarOutro');
    const selectCategoria = formProduto.querySelector('[name="categoriaId"]');
    let cadastradosNaSessao = 0;
    let enviando = false;
    carregarCategorias(selectCategoria);
    configurarNovaCategoria(formProduto, selectCategoria);

    // continuar = true mantém o usuário no formulário para cadastrar o próximo produto.
    async function enviar(botao, continuar) {
        if (enviando) return;
        limparMensagem(formProduto);
        const dados = formParaObjeto(formProduto);
        const erroValidacao = validarProduto(dados);
        if (erroValidacao) {
            mostrarErro(formProduto, erroValidacao);
            return;
        }

        enviando = true;
        iniciarCarregando(botao, 'Cadastrando...');
        try {
            await criarProduto(toProdutoDTO(dados));
            if (!continuar) {
                window.location.href = 'produtos-listagem.html';
                return;
            }
            cadastradosNaSessao += 1;
            const categoria = selectCategoria.value;
            formProduto.reset();
            selectCategoria.value = categoria; // mantém a categoria para o próximo item
            mostrarSucesso(formProduto, `“${dados.nome.trim()}” cadastrado (${cadastradosNaSessao} nesta sessão).`);
            formProduto.querySelector('[name="nome"]').focus();
        } catch (erro) {
            tratarErro(formProduto, erro);
        } finally {
            enviando = false;
            pararCarregando(botao);
        }
    }

    botaoCadastrar.addEventListener('click', () => enviar(botaoCadastrar, false));
    botaoCadastrarOutro?.addEventListener('click', () => enviar(botaoCadastrarOutro, true));

    // Enter em qualquer campo do produto = "Cadastrar e novo" (vários itens só pelo teclado).
    formProduto.addEventListener('keydown', (evento) => {
        if (evento.key !== 'Enter' || !evento.target.matches('input[name]')) return;
        evento.preventDefault();
        if (botaoCadastrarOutro) enviar(botaoCadastrarOutro, true);
        else enviar(botaoCadastrar, false);
    });
}

/** Cada linha: nome; preço de venda; preço de compra (opcional); estoque mínimo (opcional). Aceita TAB (planilha). */
function interpretarLote(texto) {
    const itens = [];
    const erros = [];
    texto.split(/\r?\n/).forEach((linha, indice) => {
        if (!linha.trim()) return;
        const n = indice + 1;
        const [nome = '', venda = '', compra = '', minimo = ''] = linha.split(/\t|;/).map((c) => c.trim());
        const numero = (v) => Number(v.replace(',', '.'));

        if (!nome) { erros.push(`Linha ${n}: informe o nome.`); return; }
        if (venda === '' || !Number.isFinite(numero(venda)) || numero(venda) < 0) { erros.push(`Linha ${n}: preço de venda inválido.`); return; }
        if (compra !== '' && (!Number.isFinite(numero(compra)) || numero(compra) < 0)) { erros.push(`Linha ${n}: preço de compra inválido.`); return; }
        if (minimo !== '' && (!Number.isInteger(numero(minimo)) || numero(minimo) < 0)) { erros.push(`Linha ${n}: estoque mínimo inválido.`); return; }

        itens.push({
            nome,
            precoVenda: numero(venda),
            precoCompra: compra === '' ? 0 : numero(compra),
            estoqueMinimo: minimo === '' ? 0 : numero(minimo),
        });
    });
    return { itens, erros };
}

function configurarLote() {
    const area = document.getElementById('loteTexto');
    const botao = document.getElementById('cadastrarLote');
    const resumo = document.getElementById('loteResumo');
    const caixa = document.getElementById('loteForm');
    if (!area || !botao || !caixa) return;

    const atualizarResumo = () => {
        const { itens, erros } = interpretarLote(area.value);
        resumo.textContent = itens.length || erros.length
            ? `${itens.length} linha(s) válida(s)${erros.length ? `, ${erros.length} com erro` : ''}`
            : '';
    };
    area.addEventListener('input', atualizarResumo);

    botao.addEventListener('click', async () => {
        limparMensagem(caixa);
        const { itens, erros } = interpretarLote(area.value);
        if (erros.length) { mostrarErro(caixa, erros.slice(0, 5).join(' ')); return; }
        if (!itens.length) { mostrarErro(caixa, 'Cole ao menos uma linha de produto.'); return; }

        iniciarCarregando(botao, 'Cadastrando lote...');
        const inicio = performance.now();
        try {
            await criarProdutosEmLote(itens);
            const segundos = ((performance.now() - inicio) / 1000).toFixed(2);
            area.value = '';
            atualizarResumo();
            mostrarSucesso(caixa, `${itens.length} produto(s) cadastrado(s) em ${segundos} s.`);
        } catch (erro) {
            tratarErro(caixa, erro);
        } finally {
            pararCarregando(botao);
        }
    });
}

function formatarMoeda(valor) {
    return Number(valor || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function criarLinhaProduto(produto) {
    return `
        <tr>
            <td>${escapeHtml(produto.codigo)}</td>
            <td>${escapeHtml(produto.nome)}</td>
            <td>${escapeHtml(produto.categoriaNome || 'Sem categoria')}</td>
            <td>${formatarMoeda(produto.precoVenda)}</td>
            <td>${escapeHtml(produto.quantidadeEstoque)}</td>
            <td><span class="status ${classeStatusEstoque(produto.situacao)}">${escapeHtml(produto.situacao)}</span></td>
            <td><div class="acoes-tabela"><button class="acao-mini excluir" data-excluir="${escapeHtml(produto.id)}" type="button">Excluir</button></div></td>
        </tr>`;
}

async function carregarListagemProdutos(tbody, termoBusca = '', categoriaId = '') {
    try {
        const produtos = (await listarProdutos({ categoriaId })).map(fromProdutoDTO);
        const termo = termoBusca.trim().toLowerCase();
        const filtrados = produtos.filter((produto) => {
            if (!termo) return true;
            return produto.nome.toLowerCase().includes(termo)
                || String(produto.codigo || '').toLowerCase().includes(termo)
                || String(produto.categoriaNome || '').toLowerCase().includes(termo);
        });
        renderizarTabela(tbody, filtrados, criarLinhaProduto, 'Nenhum produto encontrado com esses filtros.');
    } catch (erro) {
        console.error(erro);
        tbody.innerHTML = `<tr><td colspan="7">${escapeHtml(erro.message || 'Não foi possível carregar a lista de produtos.')}</td></tr>`;
    }
}

async function configurarListagem(tbody) {
    const campoPesquisa = document.getElementById('pesquisaProdutos');
    const filtroCategoria = document.getElementById('filtroCategoria');
    await carregarCategorias(filtroCategoria, true);
    const recarregar = () => carregarListagemProdutos(tbody, campoPesquisa?.value || '', filtroCategoria?.value || '');
    recarregar();
    campoPesquisa?.addEventListener('input', recarregar);
    filtroCategoria?.addEventListener('change', recarregar);

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
    if (formProduto) {
        configurarFormularioCadastro(formProduto);
        configurarLote();
    }
    const listaProdutos = document.getElementById('listaProdutos');
    if (listaProdutos) configurarListagem(listaProdutos);
});
