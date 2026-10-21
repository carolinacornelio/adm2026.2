# Inventário integrado ao POS — RyuTech

**Equipe:** Nielson e Aryell. **Rodada:** 2 — desafio 2. **Verificação técnica:** 16/10/2026.

O módulo acrescenta controle de lotes e vencimentos ao ERP existente. A entrada em Compras registra quantidade, lote e validade; produtos perecíveis exigem essas informações. O saldo anterior é preservado como legado, sem datas fictícias, e pode ser regularizado após conferência da mercadoria.

As vendas do ERP e do POS usam FEFO: consomem primeiro o lote válido que vence antes. Lotes vencidos e perecíveis sem validade não são vendáveis. A operação registra venda, distribuição dos lotes e baixa de estoque em transação, com proteção contra concorrência e dupla baixa no POS.

O inventário apresenta total físico, saldo vendável, alertas de vencidos e próximos do vencimento, pesquisa e filtros por situação/data. O relatório pode ser exportado em CSV ou impresso/salvo em PDF pelo navegador. Eventos do servidor atualizam POS e inventário após a confirmação no banco. O histórico completo do ERP permite consultar e imprimir comprovantes das vendas do ERP e do POS.

Foram aprovados 17 testes de integração em PostgreSQL temporário. A demonstração automatizada em navegador verificou a entrada por lotes, alertas, exportação, comprovante, impressão e layout móvel. Uma medição local registrou 24 ms da submissão da venda até a atualização visual do inventário, dentro da meta de demonstração de 2 s. Esse resultado não representa um SLA de produção.

O pacote de entrega inclui requisitos, fluxo/diagrama, manual, plano e resultados dos testes, evidências de interface e roteiro de implantação e validação. A apresentação ao representante simulado da RyuTech, o registro de feedback/aceite e a confirmação do orçamento continuam sob responsabilidade da equipe. O guia indica envio dos prints/vídeo e deste resumo ao Caio até 25/10.

Evidências e documentação detalhada: [ENTREGA.md](ENTREGA.md) e pasta `evidencias/`.
