/** Controller do CRUD de categorias de produtos. */
import { estaAutenticado } from './authService.js';
import { listarCategorias, criarCategoria, atualizarCategoria, removerCategoria } from './categoriasService.js';
import { escapeHtml, iniciarCarregando, pararCarregando } from './uiService.js';

let categoriaEmEdicao = null;
let ultimaCarga = 0;
let categoriasExibidas = [];

function elementos() {
    return {
        form: document.getElementById('formCategoria'),
        nome: document.getElementById('nomeCategoria'),
        salvar: document.getElementById('salvarCategoria'),
        cancelar: document.getElementById('cancelarEdicaoCategoria'),
        mensagem: document.getElementById('mensagemCategoria'),
        lista: document.getElementById('listaCategorias'),
    };
}

function mostrarMensagem(texto, sucesso = false) {
    const { mensagem } = elementos();
    mensagem.textContent = texto;
    mensagem.hidden = !texto;
    mensagem.dataset.tipo = sucesso ? 'sucesso' : '';
}

function sairDaEdicao() {
    const { nome, salvar, cancelar } = elementos();
    categoriaEmEdicao = null;
    nome.value = '';
    salvar.textContent = 'Criar categoria';
    cancelar.hidden = true;
}

function resumoSituacao(categoria) {
    if (Number(categoria.quantidade_produtos ?? categoria.quantidadeProdutos ?? 0) === 0) return '<span class="status status-neutro">Sem produtos</span>';
    const estados = [
        [categoria.estoqueOk, 'com estoque OK', 'normal'],
        [categoria.proximosMinimo, 'próximos do mínimo', 'baixo'],
        [categoria.noMinimo, 'no mínimo', 'estoque-minimo'],
        [categoria.abaixoMinimo, 'abaixo do mínimo', 'estoque-minimo'],
        [categoria.esgotados, 'esgotados', 'estoque-esgotado'],
    ];
    return estados.filter(([quantidade]) => Number(quantidade) > 0)
        .map(([quantidade, texto, classe]) => `<span class="status ${classe}">${Number(quantidade)} ${texto}</span>`).join(' ')
        + `<small class="status-detalhe">${Number(categoria.validadeEmAlerta) || 0} produto(s) com alerta de validade</small>`;
}

function renderizar(categorias) {
    categoriasExibidas = categorias;
    const { lista } = elementos();
    if (!categorias.length) {
        lista.innerHTML = '<tr><td class="estado-vazio" colspan="4">Nenhuma categoria cadastrada.</td></tr>';
        return;
    }

    lista.innerHTML = categorias.map((categoria) => `
        <tr>
            <td>${escapeHtml(categoria.nome)}</td>
            <td>${escapeHtml(categoria.quantidade_produtos ?? categoria.quantidadeProdutos ?? 0)}</td>
            <td>${resumoSituacao(categoria)}</td>
            <td><div class="acoes-tabela">
                <button class="acao-mini" data-editar="${escapeHtml(categoria.id)}" type="button">Editar</button>
                <button class="acao-mini excluir" data-excluir="${escapeHtml(categoria.id)}" type="button">Excluir</button>
            </div></td>
        </tr>
    `).join('');
}

async function carregarCategorias(silencioso = false) {
    const { lista } = elementos();
    const consulta = ++ultimaCarga;
    try {
        if (!silencioso) lista.innerHTML = '<tr><td class="estado-vazio" colspan="4">Carregando categorias...</td></tr>';
        const categorias = await listarCategorias();
        if (consulta === ultimaCarga) renderizar(categorias);
    } catch (erro) {
        if (consulta !== ultimaCarga) return;
        if (silencioso) { mostrarMensagem('Não foi possível atualizar a situação das categorias. Os dados exibidos podem estar desatualizados.'); return; }
        lista.innerHTML = `<tr><td class="estado-vazio" colspan="4">${escapeHtml(erro.message || 'Não foi possível carregar as categorias.')}</td></tr>`;
    }
}

function configurarFormulario() {
    const { form, nome, salvar, cancelar } = elementos();

    form.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        const valor = nome.value.trim();
        if (!valor) {
            mostrarMensagem('Informe o nome da categoria.');
            return;
        }

        iniciarCarregando(salvar, categoriaEmEdicao ? 'Salvando...' : 'Criando...');
        try {
            if (categoriaEmEdicao) await atualizarCategoria(categoriaEmEdicao, valor);
            else await criarCategoria(valor);
            mostrarMensagem(categoriaEmEdicao ? 'Categoria atualizada.' : 'Categoria criada.', true);
            sairDaEdicao();
            await carregarCategorias();
        } catch (erro) {
            mostrarMensagem(erro.message || 'Não foi possível salvar a categoria.');
        } finally {
            pararCarregando(salvar);
        }
    });

    cancelar.addEventListener('click', () => {
        sairDaEdicao();
        mostrarMensagem('');
    });
}

function configurarAcoes() {
    elementos().lista.addEventListener('click', async (evento) => {
        const editar = evento.target.closest('[data-editar]');
        const excluir = evento.target.closest('[data-excluir]');

        if (editar) {
            const categoria = categoriasExibidas.find((item) => String(item.id) === editar.dataset.editar);
            if (!categoria) return;
            const { nome, salvar, cancelar } = elementos();
            categoriaEmEdicao = categoria.id;
            nome.value = categoria.nome;
            salvar.textContent = 'Salvar alteração';
            cancelar.hidden = false;
            nome.focus();
            return;
        }

        if (excluir && window.confirm('Excluir esta categoria? Produtos vinculados ficarão sem categoria.')) {
            try {
                await removerCategoria(excluir.dataset.excluir);
                mostrarMensagem('Categoria excluída.', true);
                await carregarCategorias();
            } catch (erro) {
                mostrarMensagem(erro.message || 'Não foi possível excluir a categoria.');
            }
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;
    configurarFormulario();
    configurarAcoes();
    carregarCategorias();
    const atualizar = () => { if (!document.hidden) carregarCategorias(true); };
    window.setInterval(atualizar, 30000);
    window.addEventListener('focus', atualizar);
    document.addEventListener('visibilitychange', atualizar);
});
