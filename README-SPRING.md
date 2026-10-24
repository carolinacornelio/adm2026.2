# Backend e API

Este guia é para quem desenvolve ou integra outros sistemas. Para instalar e iniciar o ERP, comece pelo [guia principal](README.md).

## Como o projeto está organizado

- **Backend web:** `src/main/java/com/erp/` — Java 21, Spring Boot e JDBC.
- **Interface:** `front-end-erp/` — HTML, CSS e JavaScript servidos pelo Spring Boot e incluídos no JAR pelo Maven.
- **Banco:** PostgreSQL. O Spring lê `application-local.properties` da raiz; a configuração de terminal antiga em `src/com/erp/` usa `db.properties`.
- **Estrutura das tabelas:** `src/main/resources/schema.sql`, aplicado na inicialização. Faça backup antes de atualizar um banco com dados importantes.

As configurações de banco são versionadas por decisão da equipe. Consulte [Banco de dados e credenciais](README.md#banco-de-dados-e-credenciais) antes de trocar a conexão. Inicie a aplicação na pasta que contém `pom.xml`.

## Autenticação

O cadastro cria a primeira sessão. Nos acessos seguintes, o login inicia um desafio de segundo fator; a sessão é criada após a confirmação. As rotas protegidas recebem `Authorization: Bearer TOKEN`.

O servidor associa cada operação à empresa do token. Sessões duram até 12 horas e são encerradas ao sair ou reiniciar o backend. O desafio de segundo fator expira em cinco minutos e aceita até três respostas incorretas. Categorias, faturamento analítico e IA exigem plano PLUS.

## Rotas da API

Todas as rotas abaixo usam o prefixo `/api`. Exceto cadastro, login e segundo fator, exigem autenticação.

### Acesso e cadastros

| Rota | Métodos | Uso |
| --- | --- | --- |
| `/auth/cadastro` | `POST` | Cria empresa e usuário. |
| `/auth/login` | `POST` | Confere as credenciais e inicia o segundo fator. |
| `/auth/segundo-fator` | `POST` | Confirma o desafio e cria a sessão. |
| `/auth/logout` | `POST` | Encerra a sessão no servidor. |
| `/produtos`, `/produtos/{id}` | `GET`, `POST`, `PUT`, `DELETE` | Lista, cria, consulta, atualiza ou desativa produtos. |
| `/pessoas`, `/pessoas/{id}` | `GET`, `POST`, `PUT`, `DELETE` | Gerencia clientes e fornecedores. Consulta e exclusão podem receber `?tipo=CLIENTE` ou `?tipo=FORNECEDOR`; atualizações informam o tipo no corpo. |
| `/categorias`, `/categorias/{id}` | `GET`, `POST`, `PUT`, `DELETE` | Gerencia categorias no plano PLUS. |

### Operação, estoque e vendas

| Rota | Métodos | Uso |
| --- | --- | --- |
| `/compras` | `GET`, `POST` | Consulta compras e registra entradas de estoque. |
| `/vendas` | `GET`, `POST` | Consulta vendas do ERP e POS ou registra venda pelo fluxo do ERP. A consulta aceita `data`, ou o intervalo `inicio` e `fim` (`AAAA-MM-DD`). |
| `/vendas/{id}` | `GET` | Consulta venda, itens, lotes e comprovante. |
| `/estoque` | `GET` | Consulta os saldos dos produtos ativos. |
| `/inventario/lotes?dias=30` | `GET` | Consulta saldos e validade dos lotes; `dias` aceita de 0 a 365. |
| `/inventario/lotes/{id}/validade` | `PUT` | Informa lote e validade real de um saldo sem data. |
| `/inventario/produtos/{id}/controle-validade` | `PUT` | Ativa ou desativa o controle de validade do produto. |
| `/inventarios-fisicos` | `GET`, `POST` | Lista contagens físicas e inicia uma sessão. |
| `/inventarios-fisicos/{id}` | `GET` | Consulta itens, contagens e divergências. |
| `/inventarios-fisicos/{id}/itens/{itemId}` | `PUT` | Salva uma contagem e sua observação. |
| `/inventarios-fisicos/{id}/aprovar` | `POST` | Aplica os ajustes auditados se todos os itens foram contados e o estoque não mudou. |

### POS, atendimento e indicadores

| Rota | Métodos | Uso |
| --- | --- | --- |
| `/pos/vendas` | `GET`, `POST` | Lista as 50 vendas POS mais recentes ou registra uma venda. |
| `/pos/vendas/{id}` | `GET` | Consulta recibo e itens de uma venda POS. |
| `/pos/eventos` | `GET` | Envia atualizações SSE autenticadas de estoque e catálogo após gravações. |
| `/chamados` | `GET`, `POST` | Lista e abre chamados da empresa. |
| `/chamados/{id}/situacao` | `PUT` | Atualiza a situação de um chamado. |
| `/alertas?pagina=0` | `GET` | Consulta notificações do usuário autenticado. Cada página tem até 50 itens. |
| `/alertas/verificar` | `POST` | Atualiza alertas operacionais e consulta os não lidos do usuário. |
| `/alertas/lidos` | `POST` | Marca notificações como lidas para o usuário. |
| `/dashboard/indicadores` | `GET` | Consulta indicadores que incluem vendas do POS. |
| `/dashboard/faturamento?mesReferencia=AAAA-MM` | `GET` | Consulta faturamento mensal; exige PLUS. |
| `/ia/perguntar` | `POST` | Envia pergunta ao assistente; exige PLUS e Ollama disponível. |

## Regras importantes

- As consultas e alterações são isoladas por empresa autenticada.
- Compras de produtos com validade obrigatória precisam de lote e data. Vendas ERP e POS usam FEFO e bloqueiam lotes vencidos ou sem validade obrigatória.
- O POS grava venda, pagamento informado pelo operador e baixa de estoque na mesma transação. Uma `chaveOperacao` permite verificar novamente uma venda sem duplicá-la.
- Carrinhos guardados no POS não reservam estoque. A venda exige conexão com o servidor.
- Pix e cartão são confirmados fora do sistema. Não há integração de cobrança nem emissão fiscal.
- A IA só consulta os indicadores permitidos; não abre chamados, salva contagens ou aprova ajustes.

## Compilar e testar

```powershell
mvn clean verify
```

Os testes usam PostgreSQL temporário e não precisam da base compartilhada. Para gerar o JAR, use `mvn clean package`. Mais detalhes de uso: [POS](POS-README.md), [IA](IA-ASSISTENTE-README.md) e [inventário físico](docs/integracao/INVENTARIO-FISICO.md).
