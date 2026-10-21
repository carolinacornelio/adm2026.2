# Registro histórico: unificação da IA com o ERP

Este arquivo registra a integração inicial entre versões do projeto. Não é um guia de instalação ou de execução atual; consulte [README.md](../../README.md), [IA-ASSISTENTE-README.md](../../IA-ASSISTENTE-README.md) e [README-SPRING.md](../../README-SPRING.md) para as instruções vigentes.

Esta versão foi montada a partir de duas versões do projeto:

- Base da versão robusta do Assistente IA com Ollama + Gemma 3.
- Funcionalidades gerais da branch Aryll, especialmente planos, preços separados de compra/venda, validações e movimentações do ERP.

## O que foi preservado da IA

- `IAService`
- `IAController`
- `OllamaClient`
- `IAConsulta`
- `IAHistoricoItem`
- `IAPerguntaRequest`
- `IAPlano`
- `IATool`
- tela e serviços do Assistente IA
- configuração `OLLAMA_ENABLED`, `OLLAMA_URL` e `OLLAMA_MODEL`

## O que foi incorporado da versão Aryll

- cadastro/seleção de plano
- preços separados de compra e venda
- validações de produto/pessoa
- movimentações de estoque/compras/vendas
- migração idempotente do banco em `src/main/resources/schema.sql`
- correções relacionadas aos planos

## Banco

O Spring Boot executa `schema.sql` na inicialização (`spring.sql.init.mode=always`).
A estrutura é preparada para bases novas e contém migrações `IF NOT EXISTS` para partes adicionadas depois.

## Ollama

Padrão:

- URL: `http://localhost:11434`
- Modelo: `gemma3:4b`

Esses valores podem ser alterados em `application-local.properties` ou por variáveis de ambiente.
