# Assistente IA — Gemma 3 4B

Cada pergunta passa pelo modelo local em duas etapas: classificação e planejamento juntos, depois redação estruturada com os dados oficiais e decisão de escopo. Não existem atalhos de resposta por palavras-chave nem respostas de negócio de reserva quando o modelo falha.

## Fluxo

1. O usuário envia pergunta e histórico recente para `POST /api/ia/perguntar`.
2. O modelo classifica a solicitação atual e planeja ferramentas de leitura no mesmo JSON, com `permitido` obrigatório, sem SQL livre. Solicitações fora do escopo são recusadas antes da consulta ao banco.
3. O backend valida ferramentas, datas, filtros, número de venda e prazo de validade. O plano admite até quatro consultas e pode ser corrigido uma vez pelo próprio modelo se for inválido.
4. As consultas usam a empresa da sessão autenticada. Preços, estoque físico/vendável, lotes, comprovantes e demais dados vêm do PostgreSQL; regras de uso vêm do contexto documentado do projeto.
5. Totais, diferenças e percentuais são calculados pelo backend com `BigDecimal`.
6. O modelo recebe os resultados, limites e histórico e formula a resposta completa, fundamentando as conclusões nos dados recebidos.
7. O backend exige autorização explícita e texto válido na resposta estruturada antes da exibição. Falha de modelo ou de plano retorna erro explícito ao frontend. Uma única consulta é executada por instância de cada vez; as demais recebem HTTP 429.

## Proteções

- Ferramentas exclusivamente de leitura, com filtros de empresa definidos pelo backend.
- O frontend não acessa o banco nem o Ollama diretamente.
- Consultas não selecionam senhas, tokens, documentos, telefones, e-mails ou endereços.
- Listas de até 200 registros e amostras ajustadas à janela de contexto, com toda redução informada ao modelo. Agregados de inventário cobrem todos os registros elegíveis.
- Estoque vendável usa a mesma regra do ERP/POS e exclui lotes vencidos ou validade obrigatória pendente. O modelo recebe lotes liberados e bloqueados em grupos separados, com a condição de cada lote.
- Identificadores de comprovantes ficam restritos a números presentes na conversa e à empresa autenticada. Consultas idênticas do plano são deduplicadas antes da leitura.
- O modelo não pode executar alterações. Respostas em linguagem natural devem ser conferidas em decisões importantes, pois o modelo pode interpretar dados incorretamente.

Configuração e exemplos em [IA-ASSISTENTE-README.md](IA-ASSISTENTE-README.md).
