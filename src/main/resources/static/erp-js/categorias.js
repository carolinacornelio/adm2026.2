/** Controller do CRUD de categorias de produtos. */
import { estaAutenticado } from './authService.js';
import { listarCategorias, criarCategoria, atualizarCategoria, removerCategoria } from './categoriasService.js';
import { escapeHtml, iniciarCarregando, pararCarregando } from './uiService.js';

let categoriaEmEdicao = null;

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

function renderizar(categorias) {
    const { lista } = elementos();
    if (!categorias.length) {
        lista.innerHTML = '<tr><td class="estado-vazio" colspan="3">Nenhuma categoria cadastrada.</td></tr>';
        return;
    }

    lista.innerHTML = categorias.map((categoria) => `
        <tr>
            <td>${escapeHtml(categoria.nome)}</td>
            <td>${escapeHtml(categoria.quantidade_produtos ?? categoria.quantidadeProdutos ?? 0)}</td>
            <td><div class="acoes-tabela">
                <button class="acao-mini" data-editar="${escapeHtml(categoria.id)}" type="button">Editar</button>
                <button class="acao-mini excluir" data-excluir="${escapeHtml(categoria.id)}" type="button">Excluir</button>
            </div></td>
        </tr>
    `).join('');
}

async function carregarCategorias() {
    const { lista } = elementos();
    try {
        lista.innerHTML = '<tr><td class="estado-vazio" colspan="3">Carregando categorias...</td></tr>';
        renderizar(await listarCategorias());
    } catch (erro) {
        lista.innerHTML = `<tr><td class="estado-vazio" colspan="3">${escapeHtml(erro.message || 'Não foi possível carregar as categorias.')}</td></tr>`;
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
            const categoria = (await listarCategorias()).find((item) => String(item.id) === editar.dataset.editar);
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
});
