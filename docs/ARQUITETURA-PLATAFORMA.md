# Arquitetura da plataforma ADM2026 — guia para as equipes

Este documento é o contrato que **todo módulo novo deve seguir**. Cobre identidade, empresa (tenant), permissões, padrão de API e as APIs de funcionários, perfis, Squads, OKRs e financeiro.

> **Estado de validação:** o código foi escrito e checado quanto à **sintaxe** (javac em modo parse, `node --check`). **Não foi compilado com Maven nem executado contra PostgreSQL** (o ambiente de desenvolvimento desta entrega não tinha Maven, PostgreSQL nem rede). Rode `mvn clean verify` antes de integrar; ver seção 11.

## 1. Visão geral

```
Navegador (HTML/JS, apiService.js)
   │  Authorization: Bearer <token>
   ▼
PermissaoInterceptor  ──► Autorizacao.contexto()/exigir()  ──► CurrentUser ► AuthService (sessões em memória)
   │ (regra por método+rota)                                      │
   ▼                                                              ▼
Controller (só HTTP) ► Service (regras, @Transactional) ► JdbcTemplate ► PostgreSQL
```

* **Controller**: recebe HTTP, resolve `Contexto` via `Autorizacao`, delega ao service.
* **Service**: regras de negócio, validação, transações. Recebe `empresaId` (ou `Contexto`) **como parâmetro**, nunca lê do payload.
* **Banco**: `JdbcTemplate`, SQL explícito, sempre filtrando `empresa_id`.

## 2. Autenticação (inalterada para o frontend)

1. `POST /api/auth/login` → desafio do segundo fator → `POST /api/auth/segundo-fator` → `{token, usuario}`.
2. Token Bearer, sessão de 12 h em memória (`AuthService`). Logout revoga.
3. **Novo:** o login recusa conta ou empresa desativada; `AuthService.revogarSessoesDoUsuario(id)` derruba todas as sessões (usado ao desativar funcionário ou redefinir senha); `POST /api/auth/alterar-senha`; `GET /api/auth/me` devolve identidade e permissões efetivas.
4. Funcionário criado pelo proprietário não tem perguntas de segurança: no primeiro login o fluxo existente de "configuração necessária" as coleta.

## 3. Identificação da empresa (tenant)

* **Regra de ouro:** `empresa_id` **nunca** vem do navegador. Vem do token → `Autorizacao.Contexto.empresaId()`.
* Todo `SELECT/UPDATE/DELETE` leva `AND empresa_id = ?`. Registro de outra empresa responde **404** igual a registro inexistente (`NaoEncontradoException`), sem vazar existência.
* O banco reforça: tabelas novas usam **chaves estrangeiras compostas `(id, empresa_id)`**; é impossível gravar Squad de A com integrante de B mesmo com bug no código.
* Cada requisição reconfirma no banco que usuário e empresa estão **ativos** (`Autorizacao.contexto`). Desativar vale na hora.

```java
Autorizacao.Contexto c = aut.contexto(authorization);   // usuarioId, empresaId, proprietario, permissoes
service.listar(c.empresaId());
```

## 4. Permissões

Modelo: `permissoes` (catálogo global) ← `perfil_permissoes` → `perfis_acesso` (por empresa) ← `usuario_perfis` → `usuarios`. Um usuário pode ter vários perfis; a permissão efetiva é a união.

**Proprietário** (`usuarios.proprietario = true`): acesso total, independente de perfil — por isso nenhuma edição de perfil o tranca fora. Não pode ser desativado, nem ter senha redefinida por terceiros; o perfil Proprietário é imutável e nunca atribuível a funcionários; funcionários nunca são criados como proprietários.

**Anti-escalada:** quem não é proprietário só pode conceder (em perfis ou ao atribuir perfis) permissões que ele próprio possui.

Catálogo inicial: `dashboard.view`, `dashboard.strategic`, `company.view|update`, `users.view|manage`, `permissions.manage`, `products.view|create|update`, `people.view|manage`, `sales.view|create`, `purchases.view|create`, `pos.use`, `stock.view|update`, `finance.view|create|update`, `squads.view|manage`, `okrs.view|manage|update`, `support.use`, `ai.use`.

Perfis iniciais (copiados de `perfis_modelo_permissoes` ao criar a empresa): Proprietário, Gerente, Vendedor, Estoquista, Financeiro. Empresas existentes: dono → Proprietário; demais usuários → Gerente (preserva o acesso operacional que já tinham).

### Onde a permissão é aplicada

`PermissaoInterceptor.REGRAS` é a **fonte única**: lista ordenada (método, padrão de rota, permissão); vale a primeira que casar. **Rota `/api/**` sem regra é negada (403).** Marcadores: `#publica`, `#autenticada`. Esconder botões no frontend é só conveniência (`permissoesService.js`).

## 5. Padrões de API

* Prefixo `/api`, nomes de recurso em português no plural (`/funcionarios`, `/squads`), sub-recursos para ações (`/financeiro/{tipo}/{id}/pagamento`).
* Sucesso: o objeto/lista diretamente (JSON), datas `AAAA-MM-DD`, dinheiro como número decimal (BigDecimal no backend).
* Erro: `{"message": "texto amigável"}` com **400** validação, **401** sessão inválida/conta desativada, **403** sem permissão, **404** inexistente/outra empresa, **409** conflito de unicidade, **503** banco indisponível. O `apiService.js` já exibe `message`.
* Entrada: use `Dados` (texto/inteiro/decimal/data/opcao) para validar payloads.

## 6. Como integrar um módulo novo (checklist)

1. Tabelas com `empresa_id INTEGER NOT NULL REFERENCES empresas(id)`; se referenciar usuário/Squad/objetivo, use FK composta `(x_id, empresa_id)` (exige índice único `(id, empresa_id)` na tabela referenciada — já existe em `usuarios`, `squads`, `objetivos`, `perfis_acesso`).
2. Migração aditiva e idempotente em `schema.sql` (`IF NOT EXISTS`; evite `DO $$`, o runner do Spring divide por `;`).
3. Novas permissões: `INSERT ... ON CONFLICT (codigo)` em `permissoes`. O perfil Proprietário as recebe sozinho; adicione às linhas de `perfis_modelo_permissoes` se algum perfil inicial deve tê-las.
4. **Registre as rotas em `PermissaoInterceptor.REGRAS`** (sem isso, 403).
5. Controller só HTTP; `Contexto c = aut.contexto(header)`; service recebe `c`/`empresaId`.
6. Frontend: chamadas só via `apiService` num `xxxService.js`; texto do servidor sempre por `esc()` (`adminUi.js`); declare `<body data-permissao="...">` e adicione o link em `permissoesService.js`.
7. Teste de isolamento: crie duas empresas e prove que B não lê nem altera dados de A (modelo em `PlataformaIntegrationTest`).

## 7. Banco de dados (novo/alterado)

| Tabela | Notas |
|---|---|
| `empresas` (+`status`, `updated_at`) | `status`: `ATIVA` |
| `usuarios` (+`ativo`, `proprietario`, `updated_at`) | um proprietário por empresa (índice parcial único); índice único `(id, empresa_id)` |
| `permissoes`, `perfis_acesso`, `perfil_permissoes`, `usuario_perfis`, `perfis_modelo_permissoes` | ver seção 4 |
| `squads`, `squad_membros` | líder e membros via FK composta |
| `objetivos`, `resultados_chave`, `historico_resultados_chave` | `CHECK (meta <> valor_inicial)`, `periodo_fim >= periodo_inicio` |
| `categorias_financeiras`, `financeiro_historico` | novas |
| `contas_pagar`, `contas_receber` (+`categoria_id`, `origem`, `observacao`, `created_at`, `updated_at`, `cancelado_em`, `valor_pago`/`valor_recebido`) | índices únicos parciais `(empresa_id, compra_id)` e `(empresa_id, venda_id)` impedem lançamento duplicado |

Nada é apagado. A coluna legada `usuarios.perfil` permanece (texto), sem função de autorização.

> **Atenção ao aplicar em base existente:** se `contas_receber`/`contas_pagar` já tiverem **duas linhas para a mesma venda/compra**, a criação dos índices únicos falhará. Verifique antes: `SELECT venda_id, COUNT(*) FROM contas_receber WHERE venda_id IS NOT NULL GROUP BY 1 HAVING COUNT(*)>1;` (idem `compra_id`).

## 8. APIs

Todas exigem `Authorization: Bearer <token>`. Permissão entre parênteses.

### Funcionários
| Método e rota | Permissão | Corpo / observação |
|---|---|---|
| `GET /api/funcionarios?status=ativos\|inativos&busca=` | users.view | lista com `perfis[]` e `squads[]` |
| `GET /api/funcionarios/{id}` | users.view | |
| `POST /api/funcionarios` | users.manage | `{nome,email,senha,perfis:[ids],squads:[ids]}` (≥1 perfil) |
| `PUT /api/funcionarios/{id}` | users.manage | `{nome?,email?,perfis?,squads?}` |
| `PUT /api/funcionarios/{id}/status` | users.manage | `{ativo:boolean}`; encerra sessões |
| `POST /api/funcionarios/{id}/senha` | users.manage | `{senha?}`; sem `senha` gera temporária → `{"redefinida":true,"senhaTemporaria":"..."}` (única vez) |

```json
POST /api/funcionarios
{"nome":"Vera","email":"vera@loja.com","senha":"segredo1","perfis":[12],"squads":[3]}
→ {"id":41,"nome":"Vera","email":"vera@loja.com","ativo":true,"proprietario":false,
   "perfis":[{"id":12,"nome":"Vendedor"}],"squads":[{"id":3,"nome":"Vendas"}],"criadoEm":"..."}
```

### Empresa, perfis, permissões
`GET|PUT /api/empresa` (company.view/update; edita `nomeFantasia`, `documento`) · `GET /api/permissoes` (users.view) · `GET /api/perfis`, `GET /api/perfis/{id}` (users.view) · `POST /api/perfis`, `PUT /api/perfis/{id}`, `DELETE /api/perfis/{id}` (permissions.manage); corpo `{nome,descricao,permissoes:["products.view",...]}`. Perfil inicial não é excluível; perfil em uso não é excluível.

### Squads
`GET /api/squads?status=ATIVA|INATIVA`, `GET /api/squads/{id}`, `GET /api/squads/{id}/candidatos` (squads.view) · `POST /api/squads {nome,descricao,liderId?,integrantes?}`, `PUT /api/squads/{id} {nome?,descricao?,status?}`, `POST /api/squads/{id}/integrantes {usuarios:[ids]}`, `DELETE /api/squads/{id}/integrantes/{usuarioId}`, `PUT /api/squads/{id}/lider {usuarioId|null}` (squads.manage). Líder é sempre integrante; remover o líder da Squad (ou desativá-lo) deixa a Squad sem líder.

### OKRs
`GET /api/okrs/objetivos?status&squadId&responsavelId&de&ate`, `GET /api/okrs/objetivos/{id}`, `GET /api/okrs/resultados/{id}`, `GET /api/okrs/resultados/{id}/historico`, `GET /api/okrs/progresso` (okrs.view) · `POST|PUT|DELETE /api/okrs/objetivos[...]`, `POST /api/okrs/objetivos/{id}/resultados`, `PUT|DELETE /api/okrs/resultados/{id}` (okrs.manage; DELETE **arquiva/cancela**, preserva histórico) · `PUT /api/okrs/resultados/{id}/progresso {valorAtual,comentario}` (okrs.update; quem não tem okrs.manage só atualiza resultados de que é responsável).

Progresso = `(atual − inicial) / (meta − inicial) × 100`, limitado a 0–100; funciona para metas de redução; `meta = inicial` é recusado. Resultado conclui aos 100%; objetivo conclui quando todos os resultados ativos concluem (e reabre se algum deixar de estar). "Atrasado" = objetivo ativo com `periodoFim` anterior a hoje (fuso America/Fortaleza).

### Dashboard estratégico
`GET /api/dashboard/estrategico` (dashboard.strategic). Cada bloco só vem se o usuário tem a permissão: `funcionariosAtivos` (users.view), `totalSquads`/`squadsAtivas` (squads.view), `objetivosAtivos`, `resultadosEmAndamento`, `progressoMedio`, `objetivosConcluidos`, `objetivosAtrasados`, `desempenhoPorSquad[]` (okrs.view).

### Financeiro (`{tipo}` = `pagar` | `receber`)
`GET /api/financeiro/resumo`, `GET /api/financeiro/{tipo}?situacao=PENDENTE|VENCIDO|LIQUIDADO|CANCELADO&de&ate&categoriaId&busca`, `GET /api/financeiro/{tipo}/{id}` (com histórico), `GET /api/financeiro/categorias?tipo=` (finance.view) · `POST /api/financeiro/{tipo} {descricao,valor,vencimento,categoriaId?,observacao?}`, `POST /api/financeiro/categorias {nome,tipo}` (finance.create) · `PUT /api/financeiro/{tipo}/{id}`, `POST /api/financeiro/{tipo}/{id}/pagamento {data?}`, `POST /api/financeiro/{tipo}/{id}/cancelamento {motivo?}`, `PUT /api/financeiro/categorias/{id}` (finance.update).

Regras: `BigDecimal`, > 0, ≤ 2 casas, ≤ 99.999.999,99. Liquidar é `UPDATE … WHERE status='pendente'` (repetir não duplica). Cancelar é lógico e só vale para pendentes. Lançamentos gerados por compra/venda/POS não têm o valor editável.

**Integração:** `POST /api/vendas` e `POST /api/compras` geram, na **mesma transação**, a conta a receber/pagar (`origem` VENDA/COMPRA), pendente com vencimento = `vencimento` informado ou a data da operação; `"pago": true` já a liquida. O POS gera conta já `recebido` (`origem` POS). Reprocessar não duplica (índice único + `ON CONFLICT DO NOTHING`).

## 9. Decisões e limitações conhecidas

* **Interceptor central em vez de anotações:** os testes existentes chamam controllers diretamente; o interceptor não os afeta e dá um único ponto auditável.
* **Sessões ainda em memória** (reiniciar o servidor derruba todos; não há múltiplas instâncias). Revogação imediata funciona porque também há checagem no banco a cada requisição.
* **Pacote plano `com.erp`**, mantendo a convenção do projeto.
* **Frontend publicado:** `src/main/resources/static/` é a única origem empacotada pelo Maven e servida em `classpath:/static`. `front-end-erp/` é mantido como espelho para desenvolvimento independente.
* Reativar funcionário preserva Squads e perfis; desativar apenas retira a liderança.
* Resposta de API de erro 404 para dado de outra empresa é intencional.
* Pagamento parcial não existe: liquida-se o valor integral.

## 10. Frontend

Novas páginas: `empresas`, `funcionarios`, `perfis-acesso`, `squads`, `squad-detalhes`, `okrs`, `objetivo-detalhes`, `financeiro` (+ seção "Visão estratégica" em `dashboard.html`). A pasta **Administração** do menu é injetada por `permissoesService.js` (sem editar as demais páginas) conforme as permissões do usuário.

## 11. Executar e validar

```bash
mvn clean verify        # roda todos os testes (PostgreSQL temporário via embedded-postgres)
mvn spring-boot:run     # na raiz do projeto (lê application-local.properties)
```
Novos testes: `PlataformaIntegrationTest` (isolamento A×B, permissões, anti-escalada, desativado, Squads, OKRs, financeiro sem duplicidade, migração repetível).
