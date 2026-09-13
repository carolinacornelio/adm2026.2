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
HTML/CSS → JavaScript → (futuramente) API REST Spring Boot → PostgreSQL

## Atenção
`localStorage` é apenas uma simulação para a Etapa 2. Não é seguro para produção e não deve armazenar senhas dessa forma. Na Etapa 3, o login/cadastro será migrado para o Spring Boot, com senha protegida por hash e autenticação adequada.

## Como testar
1. Abra `cadastro.html`.
2. Crie uma conta.
3. Você será direcionado ao dashboard.
4. Cadastre produtos e pessoas.
5. Volte às listagens para verificar os dados.
6. Faça logout e tente acessar uma tela do ERP: deverá voltar ao login.
7. Faça login novamente com a conta criada.

## Próxima etapa
Criar o projeto Spring Boot, entidades, repositórios, serviços e controllers, e trocar as operações `localStorage` por `fetch` para `/api/...`.
