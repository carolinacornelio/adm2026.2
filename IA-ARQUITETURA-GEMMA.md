# Assistente IA — Gemma 3:4b

A IA do ERP usa o Gemma 3:4b somente para interpretar linguagem natural e produzir análises qualitativas.

## Fluxo

1. O usuário envia uma pergunta para `POST /api/ia/perguntar`.
2. O `IAService` aplica bloqueios locais de escopo, escrita e dados sensíveis.
3. O Gemma planeja consultas em JSON usando somente ferramentas de leitura autorizadas.
4. O backend valida o plano, as datas e os filtros.
5. O backend consulta o PostgreSQL usando `empresaId` autenticado.
6. Totais, diferenças e percentuais são calculados pelo Java com `BigDecimal`.
7. Para perguntas factuais, a resposta é montada pelo backend, sem depender de cálculo do modelo.
8. Para perguntas analíticas, o Gemma recebe o contexto oficial e só pode produzir uma observação qualitativa, sem números novos.

## Proteções

- A IA não possui ferramenta de INSERT, UPDATE ou DELETE.
- O frontend não acessa o Neon nem o Ollama.
- O assistente usa apenas a empresa do usuário autenticado.
- Senha, token, credencial, documento, CPF, CNPJ, telefone, celular, e-mail e endereço não são enviados ao modelo.
- O faturamento usa `vendas.total`; estoque nunca é usado como faturamento.
- Listas são limitadas para evitar respostas e consultas gigantes.
