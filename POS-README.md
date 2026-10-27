# Frente de caixa (POS)

O POS registra vendas no balcão e usa o mesmo catálogo e estoque do ERP. Para instalar e iniciar o sistema, siga o [guia principal](README.md). O POS não precisa do Ollama.

## Registrar uma venda

1. Entre no sistema e escolha **POS**. Use **Dashboard** para voltar ao ERP.
2. Pesquise pelo nome ou código do produto. `F2` leva à busca.
3. Adicione os produtos, ajuste quantidades e, se necessário, informe o cliente e o desconto.
4. Escolha a forma de pagamento e finalize. `F9` também inicia a finalização.
5. Abra **Histórico de vendas** para consultar o comprovante e imprimir ou salvar em PDF.

O sistema grava a venda e atualiza o estoque na mesma transação. Alterações de produtos e estoque chegam aos caixas conectados.

## Pagamentos e comprovantes

- **Dinheiro:** informe o valor recebido; o POS calcula o troco.
- **Pix:** confirme os itens para exibir o QR Code da empresa, confira o recebimento e pressione **Confirmar Pix recebido e finalizar venda**. O POS só registra a venda depois dessa confirmação manual; não acompanha a leitura do QR nem consulta o banco.
- **Cartão:** confirme o recebimento na maquininha e marque a confirmação no POS. O sistema não gera cobranças.
- O comprovante é informativo e não substitui NFC-e ou outro documento fiscal.
- O histórico do POS mostra as 50 vendas mais recentes. O **Histórico de vendas** do ERP permite consultar vendas do ERP e POS por data.

## Estoque e conexão

- As vendas consomem primeiro os lotes válidos com vencimento mais próximo (FEFO). Lotes vencidos ou com validade obrigatória pendente não podem ser vendidos.
- A validade do dia ainda permite venda; a partir do dia seguinte o lote fica vencido.
- O saldo físico pode incluir unidades bloqueadas. O catálogo do POS mostra a quantidade disponível para venda.
- **Guardar venda** salva o carrinho nesta aba, sem reservar produtos. Ao retomar, preços e estoque são verificados novamente.
- Se a conexão falhar durante a finalização, use **Verificar venda / tentar novamente** para conferir ou reenviar a mesma operação sem duplicá-la.
- O POS exige conexão com o servidor; não há operação offline.

Alertas de estoque e validade aparecem no ERP. Para consultar lotes e contagens físicas, veja **Operação → Estoque**. Os detalhes da API estão em [README-SPRING.md](README-SPRING.md).
