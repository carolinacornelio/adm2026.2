/**
 * apiService.js
 * ---------------------------------------------------------------------------
 * CAMADA DE SERVIÇOS (HTTP)
 * ---------------------------------------------------------------------------
 * Este é o ÚNICO arquivo do projeto que deve saber como fazer uma requisição
 * HTTP. Nenhum outro módulo deve usar `fetch` diretamente — todos passam por
 * aqui. Isso é o que chamamos de "camada de acesso a dados" (data access
 * layer): se um dia trocarmos Fetch por Axios, ou mudarmos a URL do backend,
 * só este arquivo precisa mudar.
 *
 * INTEGRAÇÃO COM SPRING BOOT (Etapa 3)
 * ---------------------------------------------------------------------------
 * Quando o backend em Java Spring Boot estiver no ar, basta ajustar a
 * constante API_BASE_URL abaixo para apontar para o servidor real
 * (ex: "https://api.codescript.com/api" em produção). Nenhum outro arquivo
 * do front-end precisará ser alterado, pois todos consomem esta camada.
 * ---------------------------------------------------------------------------
 */

// TODO (Etapa 3): trocar pela URL real do backend Spring Boot.
// Exemplo em ambiente local:      http://localhost:8080/api
// Exemplo em produção (deploy):   https://SEU-DOMINIO.com/api
const API_BASE_URL = 'http://localhost:8080/api';

/**
 * Erro customizado para falhas de API.
 * Guarda o status HTTP e o corpo da resposta (quando existir), para que
 * quem chamou a função possa decidir como tratar 400, 401, 404, 500 etc.
 */
export class ApiError extends Error {
    constructor(message, status, data) {
        super(message);
        this.name = 'ApiError';
        this.status = status; // ex: 400, 401, 404, 500...
        this.data = data;     // corpo de erro devolvido pela API (se houver)
    }
}

/**
 * Monta os headers padrão de toda requisição.
 * Quando a Etapa 3 (Spring Security + JWT) estiver pronta, o token salvo no
 * login será anexado automaticamente aqui — nenhum outro arquivo precisa
 * se preocupar em enviar o token manualmente.
 */
function montarHeaders(headersExtras = {}) {
    const headers = {
        'Content-Type': 'application/json',
        ...headersExtras,
    };

    // TODO (Etapa 3): descomentar quando o login devolver um JWT do Spring.
    // const token = sessionStorage.getItem('authToken');
    // if (token) {
    //     headers['Authorization'] = `Bearer ${token}`;
    // }

    return headers;
}

/**
 * Traduz um status HTTP em uma mensagem amigável para o usuário final.
 * Mantém as telas desacopladas dos códigos HTTP "crus".
 */
function mensagemParaStatus(status) {
    if (status === 400) return 'Os dados enviados são inválidos. Verifique o formulário.';
    if (status === 401) return 'Sua sessão expirou. Faça login novamente.';
    if (status === 403) return 'Você não tem permissão para realizar esta ação.';
    if (status === 404) return 'O recurso solicitado não foi encontrado.';
    if (status === 409) return 'Já existe um registro com esses dados.';
    if (status >= 500) return 'Erro interno no servidor. Tente novamente em instantes.';
    return 'Não foi possível completar a solicitação.';
}

/**
 * Função central: monta a URL, faz o fetch, trata erro de rede (4xx/5xx) e
 * devolve o JSON já pronto para uso. Todas as demais funções deste arquivo
 * (get/post/put/patch/del) passam por aqui.
 */
async function request(endpoint, { method = 'GET', body, headers } = {}) {
    const url = `${API_BASE_URL}${endpoint}`;

    const config = {
        method,
        headers: montarHeaders(headers),
    };

    if (body !== undefined) {
        config.body = JSON.stringify(body);
    }

    let response;
    try {
        response = await fetch(url, config);
    } catch (erroDeRede) {
        // Servidor fora do ar, sem internet, CORS bloqueado, etc.
        throw new ApiError(
            'Não foi possível conectar ao servidor. Verifique sua conexão.',
            0,
            null
        );
    }

    // Requisições DELETE/PUT do Spring costumam devolver 204 (sem corpo).
    let dados = null;
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
        dados = await response.json().catch(() => null);
    }

    if (!response.ok) {
        // Se o Spring devolver um corpo de erro (ex: {"message": "..."}),
        // priorizamos essa mensagem; senão usamos uma mensagem genérica.
        const mensagem = (dados && dados.message) || mensagemParaStatus(response.status);
        throw new ApiError(mensagem, response.status, dados);
    }

    return dados;
}

/**
 * apiService
 * ---------------------------------------------------------------------------
 * API pública deste módulo. Cada método corresponde a um verbo HTTP.
 * Uso típico dentro de um "service" de domínio (ex: pessoasService.js):
 *
 *   import { apiService } from './apiService.js';
 *   const pessoas = await apiService.get('/pessoas');
 *   const nova    = await apiService.post('/pessoas', pessoaDTO);
 * ---------------------------------------------------------------------------
 */
export const apiService = {
    get: (endpoint) => request(endpoint, { method: 'GET' }),
    post: (endpoint, body) => request(endpoint, { method: 'POST', body }),
    put: (endpoint, body) => request(endpoint, { method: 'PUT', body }),
    patch: (endpoint, body) => request(endpoint, { method: 'PATCH', body }),
    delete: (endpoint) => request(endpoint, { method: 'DELETE' }),
};

/**
 * MAPA DE ENDPOINTS (Etapa 3)
 * ---------------------------------------------------------------------------
 * Referência rápida de quais rotas o backend Spring Boot deverá expor.
 * Os services de domínio (authService.js, pessoasService.js, etc.) já estão
 * escritos esperando exatamente estas rotas — basta descomentar as chamadas
 * apiService.* que estão preparadas dentro de cada um deles.
 *
 *   POST   /api/auth/cadastro        -> cria um novo usuário/empresa
 *   POST   /api/auth/login           -> autentica e devolve token + dados do usuário
 *
 *   GET    /api/pessoas              -> lista pessoas do usuário logado
 *   GET    /api/pessoas/{id}         -> busca uma pessoa
 *   POST   /api/pessoas              -> cria uma pessoa
 *   PUT    /api/pessoas/{id}         -> atualiza uma pessoa
 *   DELETE /api/pessoas/{id}         -> remove uma pessoa
 *
 *   GET    /api/produtos             -> lista produtos do usuário logado
 *   GET    /api/produtos/{id}        -> busca um produto
 *   POST   /api/produtos             -> cria um produto
 *   PUT    /api/produtos/{id}        -> atualiza um produto
 *   DELETE /api/produtos/{id}        -> remove um produto
 *
 *   GET    /api/estoque              -> situação de estoque de todos os produtos
 *
 *   GET    /api/dashboard/indicadores -> totais exibidos nos cartões do dashboard
 * ---------------------------------------------------------------------------
 */
