# Integração Spring Boot + JavaScript

## Estado da migração

O projeto agora possui duas versões do backend:

| Versão | Local | Função |
|---|---|---|
| Legada | `src/com/erp` | Aplicação de terminal com `Scanner` e JDBC manual |
| Atual | `src/main/java/com/erp` | API REST Spring Boot consumida pelo frontend |

A versão legada foi preservada para consulta. A aplicação atual inicia em `ErpApplication`.

## Principais funções

- `ErpApplication`: inicia o servidor HTTP na porta 8080.
- `AuthController`/`AuthService`: cadastro, login, BCrypt e criação de token de sessão.
- `CurrentUser`: lê o token `Bearer` e identifica usuário/empresa.
- `ProdutoController`: CRUD de produtos e isolamento por empresa.
- `PessoaController`: CRUD de clientes e fornecedores, que continuam em tabelas distintas.
- `MovimentoController`: compras, vendas e estoque. A validação e atualização do estoque acontecem em uma transação.
- `DashboardController`: calcula os indicadores diretamente no banco.
- `PlanService`/`planService.js`: normalizam os planos `BASE`, `PLUS` e `PREMIUM` e controlam recursos por `data-plano-minimo`.
- `CategoriaController`/`categoriasService.js`: CRUD de categorias de produtos, com isolamento por empresa e prevenção de nomes duplicados.
- `IAController`: assistente analítico protegido pelo plano `PLUS`, com respostas de estoque, faturamento e saída de produtos.
- `apiService.js`: único ponto do frontend que faz `fetch`, envia o token e trata erros HTTP.
- `storageService.js`: implementação antiga baseada em `localStorage`, mantida como referência, mas não usada pelos services atuais.

## Como executar

1. Instale Maven e Java 21.
2. Configure as variáveis `DATABASE_URL`, `DB_USER` e `DB_PASSWORD` ou crie
   `application-local.properties` na raiz a partir de
   `application-local.properties.example`.
3. Na raiz do projeto, execute:

```bash
mvn package
java -jar target/adm2026-1.0.0.jar
```

Como o Maven deste ambiente não está no `PATH`, também funciona usando o executável instalado em `Downloads`:

```powershell
& "C:\Users\aryel\Downloads\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd" package
java -jar target\adm2026-1.0.0.jar
```

4. Abra `http://localhost:8080/`. O JAR já contém a interface HTML, CSS e JavaScript.

Na primeira inicialização, `schema.sql` cria a estrutura ausente e migra as
colunas compatíveis sem apagar dados. O `Script.sql` continua disponível para
execução manual no PostgreSQL, caso seja necessário.

## Acesso pela rede local

O backend está configurado com `server.address=0.0.0.0` e o JAR entrega a
interface no mesmo endereço. Com o servidor em execução, os colegas na mesma
rede Wi-Fi acessam `http://IP-DA-MAQUINA:8080/`.

## Endpoints atuais

```text
POST /api/auth/cadastro
POST /api/auth/login
GET|POST|PUT|DELETE /api/produtos
GET|POST|PUT|DELETE /api/categorias (plano PLUS)
GET|POST|PUT|DELETE /api/pessoas
GET /api/estoque
GET|POST /api/compras
GET|POST /api/vendas
GET /api/dashboard/indicadores
GET /api/dashboard/faturamento?mesReferencia=AAAA-MM (plano PLUS)
POST /api/ia/perguntar (plano PLUS)
```

Depois do login, as chamadas protegidas precisam do header:

```text
Authorization: Bearer TOKEN_RECEBIDO_NO_LOGIN
```

## Diferença entre o código antigo e o atual

Antes, `Main` perguntava dados pelo terminal, `Estoque` aplicava as regras e os repositories abriam conexões JDBC manualmente. No frontend, `storageService` simulava banco e autenticação no navegador.

Agora, o navegador envia JSON para Controllers REST. Os Controllers delegam o acesso ao banco via `JdbcTemplate`; a sessão define a empresa do usuário; e compras/vendas atualizam estoque no servidor, dentro de transação. Assim, o browser não escolhe o `empresaId` e não controla a regra de estoque.

## Controle de planos

Novas empresas começam no plano escolhido no cadastro (`BASE`, `PLUS` ou `PREMIUM`), salvo em `empresas.plano_assinatura`. O login devolve o plano no objeto `usuario`, o frontend usa esse valor para ocultar recursos e o backend valida novamente o acesso.

Para testar a interface da IA, promova uma empresa de teste para `PLUS` no PostgreSQL e faça login novamente para renovar os dados da sessão:

```sql
UPDATE empresas
SET plano_assinatura = 'PLUS'
WHERE id = SEU_ID_DA_EMPRESA;
```

O assistente integrado nesta etapa é analítico: consulta o PostgreSQL e responde às perguntas suportadas, sem chamar um provedor externo de modelo de linguagem. A integração com uma API de IA externa pode ser adicionada depois, mantendo o mesmo endpoint `/api/ia/perguntar`.

O dashboard econômico usa `GET /api/dashboard/faturamento` para comparar as vendas do mês escolhido com o mês anterior. O mês pode ser alterado no campo de referência; os valores, a variação percentual e o texto de análise são calculados com dados reais da empresa autenticada.

## Limitações conhecidas

- O token atual fica em memória e é perdido quando o servidor reinicia. Para produção, substituir por JWT ou sessão persistente.
- A coluna antiga `produtos.preco` foi preservada por compatibilidade; as operações atuais usam `preco_compra` e `preco_venda`.
- Ainda não foram adicionados testes automatizados nem migrations versionadas (Flyway/Liquibase).
