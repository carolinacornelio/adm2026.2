/**
 * pessoas.js
 * ---------------------------------------------------------------------------
 * Controller do domínio "Pessoas". Um único arquivo cuida das duas telas
 * (pessoas-cadastro.html e pessoas-listagem.html) porque ambas usam
 * <script src="pessoas.js">; cada função só é ligada se o elemento da
 * respectiva tela existir na página atual.
 * ---------------------------------------------------------------------------
 */

import { listarPessoas, criarPessoa, removerPessoa } from './pessoasService.js';
import { estaAutenticado } from './authService.js';
import { toPessoaDTO, fromPessoaDTO } from './dto.js';
import {
    formParaObjeto,
    iniciarCarregando,
    pararCarregando,
    mostrarErro,
    limparMensagem,
    renderizarTabela,
    tratarErro,
    escapeHtml,
} from './uiService.js';

// ---------------------------------------------------------------------------
// Tela: pessoas-cadastro.html
// ---------------------------------------------------------------------------

function validarPessoa(dados) {
    if (!dados.nome?.trim()) return 'Informe o nome da pessoa.';
    if (!dados.documento?.trim()) return 'Informe o CPF ou CNPJ.';
    return null;
}

function configurarFormularioCadastro(formPessoa) {
    const botaoCadastrar = formPessoa.querySelector('button.primario');

    formPessoa.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        limparMensagem(formPessoa);
        const dados = formParaObjeto(formPessoa);

        const erroValidacao = validarPessoa(dados);
        if (erroValidacao) {
            mostrarErro(formPessoa, erroValidacao);
            return;
        }

        iniciarCarregando(botaoCadastrar, 'Cadastrando...');
        try {
            const pessoaDTO = toPessoaDTO(dados);
            await criarPessoa(pessoaDTO);
            window.location.href = 'pessoas-listagem.html';
        } catch (erro) {
            tratarErro(formPessoa, erro);
        } finally {
            pararCarregando(botaoCadastrar);
        }
    });
}

// ---------------------------------------------------------------------------
// Tela: pessoas-listagem.html
// ---------------------------------------------------------------------------

function criarLinhaPessoa(pessoa) {
    return `
        <tr>
            <td>${escapeHtml(pessoa.nome)}</td>
            <td>${escapeHtml(pessoa.tipoTexto)}</td>
            <td>${escapeHtml(pessoa.telefone || '-')}</td>
            <td>
                <div class="acoes-tabela">
                    <button class="acao-mini excluir" data-excluir="${escapeHtml(pessoa.id)}" data-tipo="${escapeHtml(pessoa.tipo)}" type="button">Excluir</button>
                </div>
            </td>
        </tr>
    `;
}

let versaoBusca=0;
async function carregarListagemPessoas(tbody, termoBusca = '') {
    const versao=++versaoBusca;
    try {
        const pessoas = (await listarPessoas()).map(fromPessoaDTO);
        if(versao!==versaoBusca)return;
        const filtradas = termoBusca
            ? pessoas.filter((p) => p.nome.toLowerCase().includes(termoBusca.toLowerCase()))
            : pessoas;

        renderizarTabela(tbody, filtradas, criarLinhaPessoa, 'Nenhuma pessoa cadastrada ainda.');
    } catch (erro) {
        if(versao!==versaoBusca)return;
        console.error(erro);
        tbody.innerHTML = `<tr><td colspan="4">Não foi possível carregar a lista de pessoas.</td></tr>`;
    }
}

function configurarListagem(tbody) {
    carregarListagemPessoas(tbody);

    const campoPesquisa = document.getElementById('pesquisaPessoas');
    if (campoPesquisa) {
        let buscaPendente;
        campoPesquisa.addEventListener('input', () => {
            ++versaoBusca;
            clearTimeout(buscaPendente);
            buscaPendente = setTimeout(() => carregarListagemPessoas(tbody, campoPesquisa.value), 200);
        });
        window.addEventListener('pagehide', () => clearTimeout(buscaPendente), { once: true });
    }

    // Delegação de evento: um único listener cuida de todos os botões
    // "Excluir" da tabela, mesmo os criados depois via renderizarTabela.
    tbody.addEventListener('click', async (evento) => {
        const botao = evento.target.closest('[data-excluir]');
        if (!botao) return;

        const confirmou = window.confirm('Deseja realmente excluir esta pessoa?');
        if (!confirmou) return;

        try {
            await removerPessoa(botao.dataset.excluir, botao.dataset.tipo);
            carregarListagemPessoas(tbody, campoPesquisa?.value || '');
        } catch (erro) {
            window.alert(erro.message || 'Não foi possível excluir esta pessoa.');
        }
    });
}

// ---------------------------------------------------------------------------
// Inicialização
// ---------------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', () => {
    // Reforço de segurança: mesmo que app.js já redirecione quem não está
    // logado, não faz sentido esta tela tentar buscar/exibir dados antes
    // desse redirecionamento terminar. Ver explicação completa em app.js.
    if (!estaAutenticado()) return;

    const formPessoa = document.getElementById('formPessoa');
    if (formPessoa) configurarFormularioCadastro(formPessoa);

    const listaPessoas = document.getElementById('listaPessoas');
    if (listaPessoas) configurarListagem(listaPessoas);
});
