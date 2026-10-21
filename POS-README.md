# POS — guia rápido

Para instalar e iniciar: [README principal](README.md). O POS usa o mesmo banco do ERP e não precisa do Ollama.

## Fazer uma venda

1. Após o login, escolha **POS**. Use **Dashboard** para voltar ao ERP.
2. Busque o produto por nome/código, adicione ao carrinho e ajuste as quantidades.
3. Informe cliente (opcional), desconto e forma de pagamento.
4. Confirme o recebimento e finalize. O servidor grava a venda e baixa o estoque na mesma transação.
5. Abra o comprovante para imprimir ou salvar em PDF.

Atalhos: **F2** busca; **F9** finalização. Alterações de catálogo e estoque chegam automaticamente aos caixas conectados.

## Pagamentos e histórico

- **Dinheiro:** registra recebido e calcula troco.
- **Pix/cartão:** cobre pelo banco/maquininha e confirme a aprovação no POS. Não há cobrança automática integrada.
- **Comprovante:** informativo, não fiscal; não substitui NFC-e.
- **Histórico POS:** últimas 50 vendas POS.
- **Histórico ERP:** todas as vendas, com filtros de data e comprovantes em **Operação → Histórico de vendas**.

## Estoque e cuidados

- A venda usa primeiro os lotes válidos que vencem antes (FEFO).
- Lotes vencidos e produtos com validade obrigatória pendente ficam bloqueados; vencimento hoje ainda permite venda.
- Estoque vendável: esgotado, abaixo/no mínimo, próximo do mínimo ou OK. O total físico pode incluir unidades bloqueadas.
- **Venda guardada:** carrinho salvo na aba, sem reservar estoque. Ao retomar, preços e estoque são conferidos.
- **Falha de rede ao finalizar:** use “Verificar venda / tentar novamente”. A chave da operação evita duplicar a venda.
- É necessário estar conectado ao servidor; não há venda offline.

Alertas não lidos aparecem no início do dashboard; após a leitura, ficam no histórico ao fim da página. Validade: aviso em até 30 dias, importante em até 15 e prioridade em até 3.

Detalhes: [API](README-SPRING.md) · [Inventário e documentação de entrega](docs/inventario/ENTREGA.md).
