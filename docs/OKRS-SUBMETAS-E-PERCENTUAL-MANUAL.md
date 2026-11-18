# OKRs: sub-metas e percentual manual

## Modos de acompanhamento

Cada resultado-chave possui um modo de acompanhamento:

- `METRICA`: modo anterior, calculado a partir de valor inicial, valor atual e meta numérica. É o padrão dos registros existentes.
- `SUBMETAS`: o progresso é o percentual de sub-metas concluídas sobre o total de sub-metas cadastradas. Exemplo: 2 de 4 etapas concluídas = 50%. Marcar ou desmarcar uma etapa recalcula o resultado e o objetivo.
- `MANUAL`: o responsável autorizado informa um valor entre 0 e 100%. A mudança é registrada no histórico existente do resultado-chave.

## Fluxo de uso

1. Abra OKRs e entre no objetivo.
2. Crie ou edite um resultado-chave e selecione o modo de acompanhamento.
3. No modo `SUBMETAS`, use **Adicionar sub-meta** e marque cada etapa concluída. O percentual atualiza automaticamente.
4. No modo `MANUAL`, use **Definir percentual** para informar o avanço avaliado.
5. Os indicadores do objetivo e da Squad usam o progresso calculado de cada resultado-chave.

## Persistência e compatibilidade

O `schema.sql` adiciona `modo_acompanhamento` e `progresso_manual` em `resultados_chave` e cria `submetas_resultados_chave`. A alteração é idempotente. Os registros antigos recebem `METRICA` como padrão e continuam utilizando a fórmula anterior.

As rotas de marcação de sub-metas exigem `okrs.update`; criação, edição e remoção das etapas seguem a permissão de gerenciamento de OKRs. O backend também valida empresa, objetivo/resultado ativo, modo do resultado e responsabilidade do usuário.


## Correção de visibilidade e autorização da interface (18/11/2026)

A interface de detalhes do objetivo agora identifica o usuário autenticado a partir de `/api/auth/me`. O botão de adicionar etapa e os checkboxes são exibidos/ativados para gestores (`okrs.manage`) e para responsáveis atribuídos ao objetivo/resultado que possuam `okrs.update`. Usuários somente de consulta recebem orientação explícita sobre a permissão e a atribuição necessárias.

As rotas de progresso e de criação/conclusão de sub-metas exigem sessão no interceptor e aplicam a autorização granular no `OkrService`: gestor ou usuário com `okrs.update` que esteja atribuído como responsável. A edição e remoção de sub-metas continuam restritas a `okrs.manage`.

Na tela de Squads, cada objetivo possui uma ação explícita para abrir a gestão de OKRs e etapas. O formulário de resultado-chave também exibe apenas os campos pertinentes ao modo selecionado (métrica, sub-metas ou percentual manual).
