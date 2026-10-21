# Assistente IA — guia rápido

Em **Ferramentas → Assistente IA**, consulte os dados da sua empresa (plano PLUS). Usa **Gemma 3 4B local**, via Ollama. Instalação do projeto: [README](README.md).

## Preparar

1. Instale o [Ollama](https://ollama.com/download) no computador que executa o ERP.
2. Execute `ollama pull gemma3:4b` uma vez.
3. Mantenha o Ollama aberto e inicie o ERP com `mvn spring-boot:run`.

O padrão já é `http://localhost:11434` e `gemma3:4b`. Para mudar a conexão, use `application-local.properties`:

```properties
erp.ia.ollama.enabled=true
erp.ia.ollama.url=http://localhost:11434
erp.ia.ollama.model=gemma3:4b
```

## O que perguntar

| Assunto | Exemplo |
| --- | --- |
| Menor saída | Quais produtos venderam menos neste mês, incluindo os sem vendas? |
| Estoque | Quais produtos estão próximos, no mínimo ou abaixo do mínimo? |
| Validade | Quais lotes estão vencidos ou vencem nos próximos 15 dias? |
| Pendências | Há lotes sem a validade obrigatória? |
| Vendas e datas | Quais vendas ocorreram em 20/10/2026? |
| Faturamento | Compare o faturamento deste mês com o mês passado. |
| Histórico | Quanto faturei em cada mês e o que foi vendido? |
| Atenção | Quais produtos merecem atenção por baixa saída, estoque ou validade? |

Sem período informado, vendas/compras usam o mês atual. Estoque e lotes mostram a situação atual. Menor saída inclui produtos ativos sem vendas no período; isso não significa que nunca venderam.

## Como funciona

O modelo interpreta a pergunta, o backend consulta o PostgreSQL e o modelo escreve a análise. Os dados são lidos novamente a cada pedido. O assistente não altera registros e não recebe dados pessoais de clientes/funcionários nem credenciais. Perguntas externas ao ERP e sobre código interno são bloqueadas.

Durante a consulta, aparece **“Buscando dados...”**; pergunta, botão e sugestões ficam bloqueados. A velocidade depende da máquina e do carregamento inicial. Listas grandes podem ser resumidas; consulte as telas do ERP para detalhes completos. A interpretação do modelo pode falhar.

## Se não responder

- Execute `ollama list` e confira o modelo; `ollama ps` mostra os modelos carregados.
- Se o serviço estiver parado, abra o Ollama ou execute `ollama serve`.
- No Windows, o ERP também pode iniciar o Ollama instalado em segundo plano: fechar a janela não necessariamente para o serviço.
- Há uma consulta por vez por instância. Falta de memória, timeout ou resposta inválida geram erro explícito, sem resposta pronta substituta.
- Após atualizar o código, reinicie o ERP. Logs do Ollama iniciado pelo ERP: `%LOCALAPPDATA%/ADM-ERP/logs/ollama-local.log`.

Detalhes técnicos: [arquitetura da IA](IA-ARQUITETURA-GEMMA.md).
