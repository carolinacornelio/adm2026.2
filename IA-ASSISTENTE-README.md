# Alteração do Assistente IA

Esta versão mantém o ERP existente e altera somente o módulo do Assistente IA.

## O que mudou

- `POST /api/ia/perguntar` continua sendo o mesmo endpoint.
- A pergunta deixou de depender de apenas quatro `if/else` fixos.
- O backend passa a montar um plano de consulta com ferramentas permitidas.
- O Ollama pode interpretar a pergunta e escolher quais dados são necessários.
- O backend executa as consultas e sempre filtra pela `empresa_id` do usuário autenticado.
- O Ollama recebe somente o contexto necessário para escrever a resposta.
- O frontend envia até 8 mensagens recentes para permitir perguntas de acompanhamento.
- Se o Ollama estiver indisponível, existe um fallback determinístico para as principais análises.

## Fluxo

`pergunta -> planejador -> ferramentas permitidas -> PostgreSQL -> contexto -> Ollama -> resposta`

A IA não recebe credenciais do banco e não recebe acesso direto ao PostgreSQL.

## Ollama local

Instale o Ollama e baixe o modelo configurado por padrão:

```powershell
ollama pull qwen2.5:3b
```

Deixe o Ollama em execução. O padrão usado pelo Spring Boot é:

- URL: `http://localhost:11434`
- Modelo: `qwen2.5:3b`

É possível trocar sem alterar o código:

```properties
erp.ia.ollama.enabled=true
erp.ia.ollama.url=http://localhost:11434
erp.ia.ollama.model=qwen2.5:3b
```

Essas propriedades podem ficar no `application-local.properties` da máquina, que já é importado pelo projeto.

## Arquivos novos

- `src/main/java/com/erp/IAService.java`
- `src/main/java/com/erp/IATool.java`
- `src/main/java/com/erp/IAPlano.java`
- `src/main/java/com/erp/IAPerguntaRequest.java`
- `src/main/java/com/erp/IAHistoricoItem.java`
- `src/main/java/com/erp/OllamaClient.java`

## Arquivos alterados

- `src/main/java/com/erp/IAController.java`
- `src/main/resources/static/erp-js/ia.js`
- `src/main/resources/static/erp-js/iaService.js`
- `src/main/resources/static/ia.html`
- `src/main/resources/static/css/tela.css`
- `src/main/resources/application.properties`

Nenhum controller de produtos, compras, vendas, estoque, dashboard ou cadastro foi alterado.

## Exemplos para testar

- `Quanto vendi este mês?`
- `Como estão minhas vendas este mês comparadas ao mês passado?`
- `Quais produtos estão com estoque baixo?`
- `Qual produto vendeu mais?`
- `Quais produtos estão com baixa saída?`
- `Estou vendendo bem e quais produtos estão ajudando nisso?`
- `Como estão minhas compras nos últimos 30 dias?`
- `Tenho estoque suficiente para os produtos que mais vendo?`
- Depois de perguntar `Quanto vendi este mês?`, pergunte `E comparado ao mês passado?` para testar o contexto da conversa.
