# Como usar sub-metas nos OKRs

## 1. Criar um resultado acompanhado por sub-metas

1. Acesse Administração → OKRs.
2. Abra o objetivo desejado.
3. Clique em **+ Resultado-chave**.
4. No campo **Como deseja medir esta meta?**, selecione **Sub-metas (marcar etapas concluídas)**.
5. Informe título, responsável e prazo. Os campos de valor inicial e valor-alvo são ocultados porque não são usados nesse modo.
6. Salve o resultado-chave.

## 2. Cadastrar as etapas

Na página de detalhes do objetivo, localize o cartão do resultado-chave. Na seção **Plano de etapas**, clique em **+ Adicionar etapa**. Informe o nome da etapa e, opcionalmente, uma descrição e a ordem de exibição. Repita para todas as etapas que compõem o resultado.

## 3. Atualizar o percentual

Marque a caixa ao lado de cada etapa concluída. O sistema calcula o percentual usando:

`etapas concluídas ÷ total de etapas × 100`

Exemplo: 3 etapas concluídas de 4 correspondem a 75%. Desmarcar uma etapa reverte o cálculo. Ao chegar a 100%, o resultado e o objetivo podem ser concluídos automaticamente; ao reabrir uma etapa, o status é reavaliado.

## 4. Acompanhamento manual

Para uma meta cuja avaliação seja subjetiva, escolha **Percentual manual (definir de 0% a 100%)** ao cadastrar o resultado. Depois, use **Definir percentual** no cartão do resultado. A alteração aceita valores entre 0 e 100 e permite registrar uma justificativa no histórico.

## 5. Permissões necessárias

- **Gestor de OKRs (`okrs.manage`)**: pode criar, editar e arquivar objetivos/resultados, adicionar e remover sub-metas e atualizar o progresso.
- **Responsável pelo objetivo ou resultado + permissão de atualização (`okrs.update`)**: pode adicionar sub-metas, marcar/desmarcar etapas e atualizar o progresso do resultado pelo qual responde.
- **Somente consulta (`okrs.view`)**: visualiza percentuais e etapas, mas não altera o acompanhamento.

Se aparecer o aviso de somente leitura, confira se o funcionário está atribuído como responsável no objetivo ou no resultado-chave e se o perfil dele contém a permissão **OKRs → Atualizar progresso**. A interface e o backend aplicam a mesma regra.
