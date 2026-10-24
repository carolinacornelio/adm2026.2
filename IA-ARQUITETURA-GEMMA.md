# Arquitetura do assistente de IA

O assistente usa Gemma 3 4B por meio do Ollama. O fluxo abaixo descreve a implementação atual; para instalar o modelo, consulte o [guia rápido](IA-ASSISTENTE-README.md).

## Fluxo de uma pergunta

1. O frontend envia a pergunta e o histórico recente para `POST /api/ia/perguntar`.
2. O modelo decide se a pergunta está no escopo e propõe consultas por ferramentas permitidas. Ele não recebe permissão para escrever SQL.
3. O backend valida a resposta, os filtros, as datas e os identificadores. O plano aceita até quatro consultas e pode passar por uma correção quando estiver inválido.
4. O backend executa consultas SQL preparadas, sempre limitadas à empresa da sessão. Totais e dados oficiais vêm do PostgreSQL.
5. O backend envia ao modelo os resultados permitidos e pede uma resposta estruturada, que é validada antes de chegar à tela.

O modelo não decide sozinho quais registros acessar nem altera informações do ERP. Há uma consulta de IA por vez em cada instância; outras requisições simultâneas recebem HTTP 429.

## Dados enviados ao modelo

- A pergunta e o histórico recente são enviados ao endereço configurado em `erp.ia.ollama.url`. O padrão é o Ollama local em `http://localhost:11434`; uma URL diferente pode apontar para outro computador ou serviço.
- As ferramentas acessam dados da empresa autenticada. Consultas de vendas podem enviar itens e nomes de produtos; estoque e inventário podem incluir nomes de produtos, quantidades e valores necessários para responder.
- Chamados são reduzidos a número, prioridade, situação e data. Nome do cliente, título e descrição não são consultados pela IA.
- Observações livres e nomes dos responsáveis pelo inventário físico também ficam fora do contexto.
- As ferramentas não selecionam senhas, tokens, documentos, telefones, e-mails ou endereços da base.
- Evite incluir dados sigilosos na própria pergunta. O texto digitado é enviado ao modelo configurado.

## Proteções e limites

- Ferramentas de leitura definidas pelo backend; filtros de empresa não são fornecidos livremente pelo modelo.
- Datas, número de venda, prazo de validade e situação são validados antes da consulta.
- Listas têm limite de até 200 registros e podem ser reduzidas para caber no contexto; os resumos usam os totais consultados antes dessa redução.
- A IA não aprova inventários físicos, não atualiza chamados e não executa operações de escrita.
- Respostas dependem do modelo e devem ser conferidas nas telas do ERP antes de decisões importantes.

## Configuração

Propriedades reconhecidas:

| Propriedade | Padrão | Uso |
| --- | --- | --- |
| `erp.ia.ollama.enabled` | `true` | Ativa ou desativa a integração. |
| `erp.ia.ollama.url` | `http://localhost:11434` | Endereço do serviço Ollama. |
| `erp.ia.ollama.model` | `gemma3:4b` | Modelo usado nas perguntas. |
| `erp.ia.ollama.timeout-seconds` | `180` | Tempo máximo de espera. |

Também podem ser informadas pelas variáveis `OLLAMA_ENABLED`, `OLLAMA_URL`, `OLLAMA_MODEL` e `OLLAMA_TIMEOUT_SECONDS`. As rotas e ferramentas estão em `IAController`, `IAService` e `IATool`.
