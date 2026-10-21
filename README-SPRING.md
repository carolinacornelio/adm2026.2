# Backend e API

Instalação e execução: [README principal](README.md).

- Backend: `src/main/java/com/erp/`, Java 21 + Spring Boot + PostgreSQL.
- Frontend: `front-end-erp/`, servido na porta 8080 e incluído no JAR pelo Maven.
- Configuração local: `application-local.properties` (fora do Git). Alternativa: `DATABASE_URL`, `DB_USER`, `DB_PASSWORD` no ambiente; `.env` não é carregado automaticamente.
- Schema: `src/main/resources/schema.sql`, aplicado na inicialização. Faça backup antes de atualizar uma base importante.
- `src/com/erp/` é a implementação antiga de terminal; não é o backend web.

## Autenticação

Cadastro/login inicia o segundo fator. Após a confirmação, envie `Authorization: Bearer TOKEN` nas rotas protegidas. O servidor valida a empresa da sessão; tokens expiram ao reiniciar o processo. Algumas funções exigem plano PLUS.

## Rotas

| Rota | Operação | Observação |
| --- | --- | --- |
| `/api/auth/cadastro` | `POST` | Cria empresa e usuário. |
| `/api/auth/login` | `POST` | Verifica credenciais e inicia segundo fator. |
| `/api/auth/segundo-fator` | `POST` | Conclui o desafio e inicia sessão autenticada. |
| `/api/produtos` | `GET`, `POST` | Lista e cadastra produtos da empresa. |
| `/api/produtos/{id}` | `GET`, `PUT`, `DELETE` | Consulta, atualiza e desativa produto. |
| `/api/pessoas` | `GET`, `POST` | Lista e cadastra clientes e fornecedores. |
| `/api/pessoas/{id}` | `GET`, `PUT`, `DELETE` | Consulta, altera e remove registros; a exclusão aceita `?tipo=CLIENTE` ou `?tipo=FORNECEDOR`. |
| `/api/categorias` | `GET`, `POST` | Categorias disponíveis no plano PLUS. |
| `/api/categorias/{id}` | `PUT`, `DELETE` | Atualiza ou remove categoria no plano PLUS. |
| `/api/estoque` | `GET` | Situação do estoque dos produtos ativos. |
| `/api/compras` | `GET`, `POST` | Consulta e registra compras com entrada de estoque. |
| `/api/vendas` | `GET`, `POST` | Consulta todas as vendas do ERP e POS, com filtros opcionais `data`, `inicio` e `fim` em `AAAA-MM-DD`, e registra vendas pelo fluxo geral do ERP. |
| `/api/vendas/{id}` | `GET` | Dados do comprovante, itens e lotes de uma venda da empresa autenticada. |
| `/api/inventario/lotes?dias=30` | `GET` | Saldo por lote e classificação de validade; prazo de alerta de 0–365 dias. |
| `/api/inventario/lotes/{id}/validade` | `PUT` | Regulariza lote sem data; corpo com `lote` e `validade` (`AAAA-MM-DD`). |
| `/api/inventario/produtos/{id}/controle-validade` | `PUT` | Configura exigência de validade; corpo com `controlaValidade` booleano. |
| `/api/alertas?pagina=0` | `GET` | Pendências do usuário e página de até 50 notificações lidas. |
| `/api/alertas/verificar` | `POST` | Atualiza os alertas operacionais da empresa e retorna as notificações não lidas do usuário. |
| `/api/alertas/lidos` | `POST` | Marca até 100 IDs enviados em uma lista JSON; leitura individual por usuário. |
| `/api/pos/vendas` | `GET`, `POST` | Lista as 50 últimas vendas POS da empresa e registra uma venda concluída. |
| `/api/pos/vendas/{id}` | `GET` | Retorna recibo e itens de uma venda POS da empresa. |
| `/api/pos/eventos` | `GET` | Fluxo SSE autenticado e isolado por empresa; avisa sobre alterações confirmadas no estoque e catálogo. |
| `/api/dashboard/indicadores` | `GET` | Indicadores que incluem as vendas POS. |
| `/api/dashboard/faturamento` | `GET` | Requer PLUS; aceita `?mesReferencia=AAAA-MM`. |
| `/api/ia/perguntar` | `POST` | Requer PLUS; consultas analíticas. |


## Regras de integração

- Compras de perecíveis exigem lote e validade. Vendas usam FEFO e não permitem lotes bloqueados.
- POS grava venda, itens, recebimento e estoque em uma transação. Cada pedido usa uma `chaveOperacao` UUID; repetir o mesmo pedido com a mesma chave recupera a venda sem duplicá-la.
- Eventos SSE são enviados após a confirmação no banco, separados por empresa.
- Pix/cartão são confirmados pelo operador externamente. Não há cobrança integrada nem emissão fiscal.

`mvn test` executa testes com PostgreSQL temporário separado. Detalhes de uso: [POS](POS-README.md) · [IA](IA-ASSISTENTE-README.md) · [Inventário](docs/inventario/ENTREGA.md).
