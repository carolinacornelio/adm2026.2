# Etapas 1 e 2 — Frontend preparado para o backend

## O que foi feito
- Remoção dos dados fictícios das telas de dashboard, pessoas, produtos e estoque.
- Formulários passaram a possuir `id`, `name` e eventos JavaScript.
- Cadastro de usuário e login funcionam localmente usando `localStorage` como simulação temporária.
- Cadastro/listagem de produtos e pessoas funcionam localmente.
- Dashboard calcula indicadores a partir dos dados cadastrados.
- Estoque inicia em 0 e deixa de usar números fictícios.
- Dados são separados por usuário através de `usuarioId`.
- Arquivos JavaScript foram separados por responsabilidade.

## Arquitetura preparada
HTML/CSS → JavaScript → API REST Spring Boot → PostgreSQL

## Atenção
`localStorage` permanece apenas como referência da Etapa 2. O fluxo atual usa API REST; senhas ficam no backend com BCrypt e o frontend guarda somente o token da sessão.

## Como testar
1. Abra `cadastro.html`.
2. Crie uma conta.
3. Você será direcionado ao dashboard.
4. Cadastre produtos e pessoas.
5. Volte às listagens para verificar os dados.
6. Faça logout e tente acessar uma tela do ERP: deverá voltar ao login.
7. Faça login novamente com a conta criada.

## Etapa atual
O backend Spring Boot fica em `src/main/java`, expõe `/api/auth`, `/api/produtos`, `/api/pessoas`, `/api/estoque`, `/api/compras`, `/api/vendas` e `/api/dashboard/indicadores`. Os services JavaScript chamam `apiService.js`, que centraliza `fetch`, token e tratamento de erros.

## Notas de manutenção
- `src/com/erp/Main.java`, `Estoque.java` e os repositories antigos são a versão de terminal/JDBC e foram preservados como histórico.
- `src/main/java/com/erp/ErpApplication.java` é o novo ponto de entrada do servidor.
- Controllers recebem HTTP; regras de estoque ficam na transação de `MovimentoController`; `CurrentUser` obtém o tenant a partir do token.
- O token atual é armazenado em memória para desenvolvimento. Para produção, trocar por JWT e persistir/invalidar sessões adequadamente.
- Para testar páginas ES Modules, sirva `front-end-erp` por HTTP (por exemplo, Live Server), não abra os HTMLs com `file://`.
