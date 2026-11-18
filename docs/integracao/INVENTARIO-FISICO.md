# Inventário físico e conciliação de estoque

Este é o manual da contagem física, uma função da tela **Operação → Estoque**. Para instalar e iniciar o ERP, use o [guia principal](../../README.md).

## Objetivo

Esta funcionalidade complementa o inventário existente de lotes e validade. Ela não substitui o controle FEFO, as entradas de compras ou as baixas de vendas. A contagem física é uma sessão separada, acessível em **Operação → Estoque → Conferência de estoque físico**.

## Fluxo de uso

1. Abra **Estoque** e clique em **Iniciar contagem**. O ERP cria uma sessão e registra a quantidade atual do sistema e o horário da última alteração para cada produto ativo com registro de estoque.
2. Conte os itens fisicamente. Registre a quantidade encontrada em cada linha e clique em **Salvar**. Se não houver unidades físicas, informe explicitamente `0`; campo vazio significa que ainda não foi contado.
3. Revise os resultados: falta (diferença negativa), sobra (diferença positiva), produto conferido (diferença zero) e valor estimado da divergência. O valor é uma estimativa baseada no preço de venda cadastrado, com fallback para o preço do produto.
4. Clique em **Conferir e aplicar ajustes** somente depois de revisar a contagem. O ERP exige que todos os produtos tenham uma contagem.
5. A aprovação ajusta o saldo para a quantidade contada, atualiza os lotes para manter a consistência, registra a movimentação de estoque e identifica o inventário e o usuário responsável no motivo.
6. Se o saldo ou o horário de atualização tiver mudado depois da abertura, a aprovação é bloqueada. Abra uma nova sessão e reconte para não aplicar uma diferença baseada em saldo desatualizado.

## Regras de segurança e auditoria

- Todos os endpoints filtram os registros pela empresa do token autenticado.
- A aprovação é transacional e bloqueia as linhas de estoque/produto antes de aplicar alterações.
- Não é possível aprovar uma sessão com itens sem contagem ou finalizar a mesma sessão duas vezes.
- Cada contagem registra o usuário e horário que a salvou; a sessão registra quem abriu e quem aprovou.
- Cada diferença gera uma movimentação `AJUSTE` com o número do inventário e o usuário que aprovou. A tabela `inventario_fisico_ajuste_lotes` também registra os lotes afetados e a quantidade retirada/adicionada em cada lote.
- Sobra de item com controle de validade obrigatório é adicionada sem inventar validade; o lote fica sem validade e, portanto, bloqueado para venda até a equipe conferir a embalagem e regularizar a data real pela tela existente.
- O ajuste não determina sozinho se houve furto, erro operacional ou erro de cadastro. A divergência é um indício para investigação; registre observações e compare com compras, vendas, perdas e movimentações.
- A contagem considera todos os produtos ativos que possuem registro na tabela de estoque. Produto sem registro de estoque não aparece na sessão e deve ser regularizado no cadastro antes de ser contado.

## Estrutura técnica

- Backend: `src/main/java/com/erp/InventarioFisicoController.java`
- Interface: seção adicionada a `front-end-erp/estoque.html`
- JavaScript: `front-end-erp/erp-js/inventarioFisico.js`
- Estilos: `front-end-erp/css/tela.css`
- Banco/migração idempotente: `src/main/resources/schema.sql`
- Sessões: `inventarios_fisicos`
- Itens/snapshots/contagens: `inventario_fisico_itens`

A migração usa `CREATE TABLE IF NOT EXISTS` e não remove dados existentes. Faça backup antes de atualizar qualquer ambiente com dados reais.

## Validação

Na integração ao ERP atual, os módulos JavaScript foram verificados, a suíte Maven foi executada com PostgreSQL descartável e o fluxo de tela foi validado em navegador. Os testes incluem isolamento por empresa, sobra de perecíveis, falta, estoque alterado, aprovação concorrente com contagem, rollback e migração repetida. Consulte [a análise da integração](ANALISE-E-INTEGRACAO.md) e os testes em `src/test`.
