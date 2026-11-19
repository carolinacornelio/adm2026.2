# OKRs: sub-metas e percentual manual

## Modos de acompanhamento

Cada resultado-chave possui um modo de acompanhamento:

- `METRICA`: modo anterior, calculado a partir de valor inicial, valor atual e meta numérica. É o padrão dos registros existentes.
- `SUBMETAS`: o progresso é o percentual de sub-metas concluídas sobre o total de sub-metas cadastradas. Exemplo: 2 de 4 etapas concluídas = 50%. Marcar ou desmarcar uma etapa recalcula o resultado e o objetivo.
- `MANUAL`: o responsável autorizado informa um valor entre 0 e 100%. A mudança é registrada no histórico existente do resultado-chave.

## Fluxo de uso

1. Em **Squads**, abra a equipe e selecione **Novo objetivo** para vinculá-lo automaticamente. Para acessar um objetivo existente, clique no título. Também é possível criar e abrir objetivos pela página **OKRs**.
2. Na página do objetivo, selecione **Novo resultado-chave** e escolha o modo de acompanhamento.
3. No modo `SUBMETAS`, use **Adicionar etapa** e marque cada etapa concluída. O percentual atualiza automaticamente.
4. No modo `MANUAL`, use **Definir percentual** para informar o avanço avaliado.
5. Os indicadores do objetivo e da Squad usam o progresso calculado de cada resultado-chave.

## Persistência e compatibilidade

O `schema.sql` adiciona `modo_acompanhamento` e `progresso_manual` em `resultados_chave` e cria `submetas_resultados_chave`. A alteração é idempotente. Os registros antigos recebem `METRICA` como padrão e continuam utilizando a fórmula anterior.

## Permissões

Gestores de OKRs (`okrs.manage`) podem criar objetivos e resultados-chave e editar ou remover etapas. Para adicionar etapas, marcar sua conclusão ou atualizar o percentual, basta ser gestor ou ter `okrs.update` e estar atribuído como responsável ao objetivo ou ao resultado-chave. O backend valida a empresa, a situação do objetivo e do resultado, e o modo de acompanhamento.
