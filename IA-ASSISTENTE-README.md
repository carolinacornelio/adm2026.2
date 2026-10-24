# Assistente de IA

O assistente responde perguntas analíticas sobre os dados da empresa. Ele usa **Gemma 3 4B pelo Ollama** e está disponível no plano PLUS. O ERP continua funcionando sem a IA.

## Preparar o modelo

1. Instale o [Ollama](https://ollama.com/download) no computador que executa o backend.
2. Baixe o modelo uma vez:

```powershell
ollama pull gemma3:4b
```

3. Inicie o ERP pela [instrução principal](README.md#executar-o-erp) e abra **Ferramentas → Assistente IA**.

O padrão é `http://localhost:11434`. No Windows, o ERP tenta iniciar o Ollama instalado no local padrão quando uma pergunta é enviada. Se ele não estiver instalado nesse local, inicie-o manualmente. A primeira resposta pode demorar enquanto o modelo carrega.

Para usar outra instalação, configure `erp.ia.ollama.url`, `erp.ia.ollama.model` e `erp.ia.ollama.enabled` em `application-local.properties`. Edite somente essas linhas, preservando a configuração de banco já existente. A URL também pode ser definida por `OLLAMA_URL`; veja [a arquitetura](IA-ARQUITETURA-GEMMA.md) para detalhes.

## Exemplos de perguntas

| Tema | Pergunta de exemplo |
| --- | --- |
| Vendas | Quais produtos venderam menos neste mês, incluindo os sem vendas? |
| Estoque | Quais produtos estão no mínimo ou abaixo dele? |
| Validade | Quais lotes vencem nos próximos 15 dias? |
| Compras | Quanto compramos neste mês? |
| Faturamento | Compare o faturamento deste mês com o anterior. |
| Inventário físico | Quantos inventários foram concluídos neste mês? |
| Divergências | Quais produtos tiveram faltas ou sobras nas contagens deste mês? |
| Atendimento | Quantos chamados estão abertos por prioridade? |

Vendas, compras, inventário físico e chamados usam o mês atual quando você não informa um período. Estoque e validade mostram a situação atual. Nas contagens físicas, divergências são estimativas e sessões diferentes podem incluir os mesmos produtos.

## Dados e limites

- A pergunta e o histórico recente são enviados ao modelo configurado. Por padrão, o Ollama roda localmente no computador do backend; se você configurar outra URL, os dados seguem para esse endereço. Evite digitar senhas ou outros dados sigilosos na pergunta.
- As consultas são limitadas à empresa autenticada. A IA não recebe credenciais, senhas de usuários, documentos, telefones, e-mails ou endereços da base.
- Para chamados, a IA consulta somente números, prioridade, situação e datas. Ela não recebe o nome do cliente, o título ou a descrição do chamado.
- Para inventário físico, os nomes dos responsáveis e as observações livres não são enviados. As consultas podem incluir nomes de produtos, quantidades e valores necessários à resposta.
- A IA não abre nem altera chamados, não salva contagens e não aprova ajustes. Ela pode interpretar os resultados incorretamente; confira as telas do ERP antes de tomar decisões importantes.
- Listas extensas podem ser limitadas para caber no contexto do modelo. A resposta informa quando isso ocorre.

## Solução de problemas

| Sintoma | O que fazer |
| --- | --- |
| Modelo não encontrado | Execute `ollama list`; se faltar `gemma3:4b`, execute `ollama pull gemma3:4b`. |
| Ollama indisponível | Abra o Ollama ou execute `ollama serve`. |
| Resposta lenta | A primeira pergunta pode carregar o modelo. Verifique memória disponível com `ollama ps`. |
| Pergunta falhou | Tente novamente; erros de conexão, timeout ou resposta inválida são exibidos sem inventar uma resposta alternativa. |

Após alterar o código, reinicie o ERP. Logs do Ollama iniciado automaticamente no Windows ficam em `%LOCALAPPDATA%/ADM-ERP/logs/ollama-local.log`.
