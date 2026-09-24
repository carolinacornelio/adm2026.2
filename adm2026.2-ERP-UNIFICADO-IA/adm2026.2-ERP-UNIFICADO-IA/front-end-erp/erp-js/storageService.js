/**
 * storageService.js
 * ---------------------------------------------------------------------------
 * REPOSITÓRIO TEMPORÁRIO (localStorage) — Etapa 2
 * ---------------------------------------------------------------------------
 * Enquanto o back-end em Spring Boot não existe, este módulo faz o papel de
 * "banco de dados" simulado, gravando tudo em localStorage. A ideia é que ele
 * ofereça funções com a MESMA CARA que uma API REST teria (listar, buscar,
 * criar, atualizar, remover) para que, na Etapa 3, os services de domínio
 * (pessoasService.js, produtosService.js...) troquem a implementação interna
 * por chamadas a `apiService` sem precisar mudar quem os utiliza.
 *
 * Os dados são separados por usuário através do campo `usuarioId`, exatamente
 * como descrito no README do projeto.
 *
 * ATENÇÃO: localStorage é público no navegador e não deve ser usado para
 * senhas ou dados sensíveis em produção. Isso é apenas uma simulação de
 * estudo para a Etapa 2 (ver README-ETAPAS-1-E-2.md).
 * ---------------------------------------------------------------------------
 */

const CHAVE_USUARIOS = 'codescript_usuarios';
const CHAVE_USUARIO_LOGADO = 'codescript_usuarioLogadoId';

/** Lê e faz parse de uma "tabela" (array) do localStorage. */
function lerTabela(nomeTabela) {
    const bruto = localStorage.getItem(nomeTabela);
    if (!bruto) return [];
    try {
        return JSON.parse(bruto);
    } catch {
        return [];
    }
}

/** Grava uma "tabela" (array) inteira no localStorage. */
function gravarTabela(nomeTabela, lista) {
    localStorage.setItem(nomeTabela, JSON.stringify(lista));
}

/** Gera um id simples (a API Spring devolverá IDs reais do banco no futuro). */
function gerarId() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

/** Retorna o id do usuário atualmente logado (ou null). */
function getUsuarioLogadoId() {
    return sessionStorage.getItem(CHAVE_USUARIO_LOGADO);
}

function setUsuarioLogadoId(id) {
    sessionStorage.setItem(CHAVE_USUARIO_LOGADO, id);
}

function limparUsuarioLogadoId() {
    sessionStorage.removeItem(CHAVE_USUARIO_LOGADO);
}

/**
 * CRUD genérico para uma tabela cujos registros pertencem a um usuário
 * (pessoas, produtos, compras, vendas...). Todo registro criado recebe
 * automaticamente o `usuarioId` do usuário logado, e toda leitura já filtra
 * por ele — assim cada empresa só vê os próprios dados.
 */
export const storageService = {
    listarPorUsuario(nomeTabela) {
        const usuarioId = getUsuarioLogadoId();
        return lerTabela(nomeTabela).filter((registro) => registro.usuarioId === usuarioId);
    },

    buscarPorId(nomeTabela, id) {
        const usuarioId = getUsuarioLogadoId();
        return (
            lerTabela(nomeTabela).find(
                (registro) => registro.id === id && registro.usuarioId === usuarioId
            ) || null
        );
    },

    criar(nomeTabela, dados) {
        const usuarioId = getUsuarioLogadoId();
        const tabela = lerTabela(nomeTabela);
        const novoRegistro = {
            id: gerarId(),
            usuarioId,
            ...dados,
            dataCadastro: new Date().toISOString(),
        };
        tabela.push(novoRegistro);
        gravarTabela(nomeTabela, tabela);
        return novoRegistro;
    },

    atualizar(nomeTabela, id, dadosAtualizados) {
        const usuarioId = getUsuarioLogadoId();
        const tabela = lerTabela(nomeTabela);
        const indice = tabela.findIndex((r) => r.id === id && r.usuarioId === usuarioId);
        if (indice === -1) return null;
        tabela[indice] = { ...tabela[indice], ...dadosAtualizados };
        gravarTabela(nomeTabela, tabela);
        return tabela[indice];
    },

    remover(nomeTabela, id) {
        const usuarioId = getUsuarioLogadoId();
        const tabela = lerTabela(nomeTabela);
        const novaTabela = tabela.filter((r) => !(r.id === id && r.usuarioId === usuarioId));
        gravarTabela(nomeTabela, novaTabela);
        return novaTabela.length !== tabela.length; // true se removeu algo
    },
};

/**
 * Operações específicas de autenticação (cadastro/login/logout).
 * Ficam separadas do CRUD genérico acima porque usuário é uma tabela
 * "especial": não pertence a um usuarioId, ela É o usuário.
 */
export const authStorage = {
    buscarPorEmail(email) {
        return lerTabela(CHAVE_USUARIOS).find((u) => u.email === email) || null;
    },

    criarUsuario(dadosUsuario) {
        const usuarios = lerTabela(CHAVE_USUARIOS);
        const novoUsuario = {
            id: gerarId(),
            ...dadosUsuario,
            dataCadastro: new Date().toISOString(),
        };
        usuarios.push(novoUsuario);
        gravarTabela(CHAVE_USUARIOS, usuarios);
        return novoUsuario;
    },

    iniciarSessao(usuario) {
        setUsuarioLogadoId(usuario.id);
    },

    encerrarSessao() {
        limparUsuarioLogadoId();
    },

    getUsuarioLogado() {
        const id = getUsuarioLogadoId();
        if (!id) return null;
        return lerTabela(CHAVE_USUARIOS).find((u) => u.id === id) || null;
    },

    estaAutenticado() {
        return Boolean(getUsuarioLogadoId());
    },
};
