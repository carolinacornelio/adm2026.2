# Integração de Atendimento e inventário físico

Este registro resume as funções aproveitadas dos arquivos ZIP analisados e como foram adaptadas ao ERP atual. Os ZIPs não são necessários para executar o sistema.

## O que entrou no ERP

| Módulo | O que faz | Onde usar |
| --- | --- | --- |
| Atendimento | Abre chamados, pesquisa, filtra por prioridade e atualiza a situação. | Ícone de headset no cabeçalho. |
| Inventário físico | Registra a contagem dos produtos, aponta faltas e sobras e permite aprovar ajustes auditados. | **Operação → Estoque → Conferência de estoque físico**. |
| Assistente IA | Consulta indicadores resumidos de chamados e dados das contagens físicas. | **Ferramentas → Assistente IA** no plano PLUS. |

## Como foi feita a adaptação

- O Atendimento usa a navegação, a autenticação, o serviço HTTP e o banco já usados pelo ERP.
- Chamados são isolados por empresa. O cadastro permite atualizar a situação para **Aberto**, **Em atendimento**, **Resolvido** ou **Cancelado**.
- A IA lê somente número, prioridade, situação e data dos chamados. Não lê o cliente, título ou descrição e não abre nem altera chamados.
- A contagem física foi adicionada ao módulo de estoque sem substituir lotes, validade, FEFO, POS ou alertas.
- Aprovações de inventário verificam se o estoque continua igual ao registrado na abertura e aplicam alterações em uma transação. Uma sobra não recebe validade inventada.
- As tabelas novas são aditivas e usam `IF NOT EXISTS`. A aplicação continua usando `front-end-erp/`; cópias antigas do frontend dos ZIPs não foram importadas.
- Configurações e credenciais particulares dos ZIPs não foram importadas; valem os arquivos de configuração já versionados neste projeto.

## Guias e validação

- [Manual da contagem física](INVENTARIO-FISICO.md)
- [Backend e rotas da API](../../README-SPRING.md)
- [Guia do assistente de IA](../../IA-ASSISTENTE-README.md)
- [Auditoria técnica e última validação registrada](AUDITORIA-DO-PROJETO.md)

Os testes de integração cobrem isolamento por empresa, contagem, divergências, aprovação, rollback e consultas da IA. A auditoria vinculada registra o resultado mais recente da suíte completa.
