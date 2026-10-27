from pathlib import Path
from xml.sax.saxutils import escape
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Image, PageBreak, KeepTogether
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from PIL import Image as PILImage, ImageDraw, ImageFont

root=Path.cwd()
out=root/'output/roteiro-rodada-2'
sections=[
('0:00 a 0:30','O problema e a proposta','Mostrar o fluxo ilustrado abaixo.',
'Nesta segunda rodada, o desafio da RyuTech era integrar o inventário ao ponto de venda, o POS, e controlar a validade dos produtos. O objetivo era evitar diferenças entre o estoque e o caixa e identificar mercadorias próximas do vencimento. Para atender a isso, o projeto passou a registrar os produtos por lote e validade, atualizar os saldos automaticamente e bloquear vendas sem estoque disponível.'),
('0:30 a 1:20','Entrada de mercadorias e controle por lote','Print 01: destacar as colunas Quantidade, Lote e Validade.',
'O primeiro passo é registrar a entrada da mercadoria. No cadastro do produto, podemos indicar que a validade é obrigatória. Depois, na tela de compras, informamos a quantidade, o lote e a data de vencimento. Aqui, recebemos dez unidades de leite: quatro no lote A e seis no lote B, com validades diferentes. Essas informações ficam salvas no banco de dados e permitem acompanhar cada lote separadamente. Para os perecíveis, o sistema exige lote e validade e também impede o recebimento de mercadoria já vencida.'),
('1:20 a 2:10','Consulta, alertas e relatórios','Print 02 e depois Print 06: mostrar os avisos e a tabela por lote.',
'Na tela de estoque, agora vemos o total físico e a quantidade disponível para venda. Essa diferença é importante: um produto vencido pode continuar no depósito, mas não pode ser vendido. Na demonstração, o iogurte tem duas unidades físicas e nenhuma disponível. Os avisos também destacam produtos que estão próximos do vencimento. Na consulta por lote, podemos filtrar por produto, situação e período de validade, além de escolher o prazo do alerta. O relatório pode ser exportado em CSV ou impresso e salvo como PDF pelo navegador.'),
('2:10 a 3:20','Venda no POS e atualização automática','Print 03, seguido de 02 e 04. Em gravação, manter Estoque e POS abertos.',
'Agora vamos mostrar a integração com o caixa. Antes da venda, temos dez unidades de leite. No POS, adicionamos duas unidades, conferimos o pagamento e confirmamos a venda. Ao concluir, o saldo passa de dez para oito, sem precisar atualizar a página manualmente. Por trás da tela, o POS envia a operação para uma API em Java com Spring Boot. O servidor confere o saldo e grava a venda e a baixa de estoque na mesma transação, ou seja, como uma única operação. Depois da confirmação, ele avisa as telas conectadas, que consultam o saldo atualizado. A saída segue a regra FEFO: primeiro que vence, primeiro que sai. Por isso, as duas unidades saíram do lote A, que vence antes, enquanto o lote B permaneceu com seis.'),
('3:20 a 4:10','Teste com estoque zerado','Print 05: destacar o aviso vermelho e o arroz com Sem estoque.',
'Também testamos uma situação de erro, como o guia pediu. O arroz está com estoque zerado e aparece como sem estoque no caixa. Ao tentar adicioná-lo pelo código, o sistema informa que o estoque é insuficiente e não inclui o item na venda. Essa proteção também existe no servidor: uma tentativa enviada diretamente para a API foi rejeitada. Assim, a regra vale mesmo fora da tela. Lotes vencidos e produtos com validade obrigatória ainda não informada também ficam indisponíveis para venda.'),
('4:10 a 5:00','Como foi feito e resultado','Voltar ao fluxo e terminar no comparativo 10 para 8.',
'A solução foi construída com telas em HTML, CSS e JavaScript, regras de negócio em Java com Spring Boot e armazenamento em PostgreSQL. Para a atualização automática, o servidor envia eventos às telas conectadas. Existe ainda uma consulta de segurança a cada quinze segundos enquanto a página está visível. Na demonstração, verificamos a venda, a redução de dez para oito unidades, a escolha do lote que vence primeiro, os alertas e a exportação do relatório. Com isso, o projeto reúne os principais recursos funcionais pedidos no desafio: inventário com validade, informações para acompanhar perdas e integração entre estoque e caixa.')
]

# Diagrama explicativo produzido para o vídeo; as demais imagens são capturas reais.
im=PILImage.new('RGB',(1680,360),'#f1f5fb');d=ImageDraw.Draw(im)
font='C:/Windows/Fonts/arial.ttf';bold='C:/Windows/Fonts/arialbd.ttf'
f=ImageFont.truetype(font,25);fb=ImageFont.truetype(bold,28)
boxes=[('COMPRA','Quantidade, lote e validade'),('INVENTÁRIO','Saldo e alertas por lote'),('VENDA NO POS','Conferência e baixa FEFO'),('ATUALIZAÇÃO','Estoque: 10 para 8')]
for i,(title,sub) in enumerate(boxes):
    x=30+i*420
    d.rounded_rectangle((x,105,x+360,255),radius=18,fill='#102d50')
    d.text((x+22,132),title,font=fb,fill='white');d.text((x+22,183),sub,font=f,fill='#d7eafa')
    if i<3:
        d.line((x+367,180,x+410,180),fill='#147ba7',width=6)
        d.polygon([(x+410,180),(x+397,170),(x+397,190)],fill='#147ba7')
d.text((30,28),'Fluxo simplificado de funcionamento',font=ImageFont.truetype(bold,32),fill='#102d50')
d.text((30,298),'Ilustração explicativa da solução implementada.',font=f,fill='#52677d')
im.save(out/'prints/00-fluxo-inventario.png')

md=['# Roteiro de vídeo - Rodada 2 | RyuTech','',
'Duração-alvo: 5 minutos, incluindo pausas para apontar os elementos na tela. Faça um ensaio e ajuste a velocidade da fala. Sugestão: pessoa 1 apresenta até 2:10; pessoa 2 apresenta o restante.','',
'Base: Guia Rodada 2 de ADM2.pdf, páginas identificadas como 12 e 13, e implementação atual do projeto. O foco é o Desafio 2.','']
for time,title,screen,speech in sections:
    md.extend([f'## {time} | {title}','',f'**Na tela:** {screen}','',f'**Fala:** {speech}',''])
notes=[
'Os prints são de execução real da aplicação em 27/10/2026, com dados fictícios e PostgreSQL temporário. O lote vencido representa um saldo antigo; a compra normal de mercadoria vencida é bloqueada.',
'O exemplo demonstra 10 unidades de leite antes da venda e 8 depois. FEFO conferido: lote A passou de 4 para 2; lote B permaneceu em 6. A tentativa de venda do arroz zerado foi bloqueada na interface e retornou HTTP 400 na API.',
'O guia cita latência menor que 2 segundos como exemplo de requisito. Esta captura comprova atualização automática local, mas não é uma medição completa de desempenho em rede. O JSON registra apenas o tempo observado depois da resposta da API; não apresente esse número como latência total da venda.',
'O PDF também pede requisitos formalizados e validados, design/diagrama de dados, plano de testes, validação simulada com o cliente e entrega/treinamento. O roteiro e o fluxo simplificado não comprovam a conclusão dessas etapas formais. Não afirmar aprovação do cliente, cumprimento de orçamento ou implantação de produção sem os registros correspondentes.',
'A implementação usa código web e uma API real; o guia sugere um protótipo low-code/no-code. Descrever no vídeo a tecnologia realmente utilizada.',
'Para evidenciar tempo real no vídeo, prefira gravar o POS e o Estoque lado a lado. Os prints de antes e depois ilustram a mudança, mas a gravação contínua demonstra melhor a atualização sem F5.'
]
md.extend(['## Orientações para a gravação','']+[f'- {n}' for n in notes]+['','## Imagens na ordem de uso',''])
for file in sorted((out/'prints').glob('*.png')):
    md.extend([f'### {file.stem}','',f'![{file.stem}](prints/{file.name})',''])
md.extend(['## Onde a implementação foi conferida','',
'- `front-end-erp/compras.html` e `erp-js/compras.js`: entrada com lote e validade.',
'- `front-end-erp/estoque.html` e `erp-js/estoque.js`: alertas, filtros e exportação CSV/impressão.',
'- `src/main/java/com/erp/InventarioService.java`: saldo vendável, validações e ordem FEFO.',
'- `src/main/java/com/erp/PosService.java`: venda, conferência e baixa na mesma transação.',
'- `src/main/java/com/erp/PosRealtimeService.java` e `front-end-erp/erp-js/estoqueSync.js`: eventos após confirmação, sincronização e consulta de segurança.',
'- `validacao-demonstracao.json`: resultados obtidos nesta demonstração.',''])
(out/'roteiro-5-minutos.md').write_text('\n'.join(md),encoding='utf-8')

styles=getSampleStyleSheet()
styles.add(ParagraphStyle(name='TitleCustom',fontName='Helvetica-Bold',fontSize=23,leading=27,textColor=colors.HexColor('#102d50'),spaceAfter=10))
styles.add(ParagraphStyle(name='SectionCustom',fontName='Helvetica-Bold',fontSize=12,leading=16,textColor=colors.HexColor('#126f9d'),spaceBefore=12,spaceAfter=5))
styles.add(ParagraphStyle(name='Speech',fontName='Helvetica',fontSize=10.5,leading=15,spaceAfter=8,textColor=colors.HexColor('#24364b')))
styles.add(ParagraphStyle(name='Hint',fontName='Helvetica',fontSize=9,leading=12,textColor=colors.HexColor('#607185'),spaceAfter=8))
story=[]
def p(t,style='Speech'):return Paragraph(escape(t),styles[style])
def pic(name,width=505,maxheight=286):
    path=out/'prints'/name
    with PILImage.open(path) as img:w,h=img.size
    scale=min(width/w,maxheight/h)
    return Image(str(path),width=w*scale,height=h*scale)
story += [p('Inventário integrado ao POS','TitleCustom'),p('RODADA 2 | RYUTECH | ROTEIRO PARA 5 MINUTOS','Hint'),p('Falas prontas para leitura. Reserve pequenas pausas para mostrar os elementos indicados. Sugestão: pessoa 1 até 2:10; pessoa 2 de 2:10 até o final.','Hint'),pic('00-fluxo-inventario.png',maxheight=110)]
for time,title,screen,speech in sections[:3]:
    story += [p(f'{time} | {title}','SectionCustom'),p('NA TELA: '+screen,'Hint'),p(speech)]
story += [PageBreak(),p('Demonstração e conclusão','TitleCustom'),p('Continue a fala e acompanhe a mudança do saldo.','Hint')]
for time,title,screen,speech in sections[3:]:
    story += [p(f'{time} | {title}','SectionCustom'),p('NA TELA: '+screen,'Hint'),p(speech)]
story += [p('Dica para demonstrar a integração','SectionCustom'),p('Grave Estoque e POS lado a lado: mostre 10 unidades, conclua a venda de 2 e acompanhe o saldo chegar a 8 sem F5. Os prints seguintes servem como apoio visual.','Hint'),p('Limite do que esta demonstração comprova','SectionCustom'),p('Foram conferidos a venda, os saldos, FEFO, alertas, exportação e bloqueio de estoque zero em ambiente local. Validação formal com cliente, orçamento e implantação de produção exigem registros próprios. Mais orientações e referências estão no roteiro editável.','Hint')]
boards=[
('Entrada, validade e relatório',[
('01-entrada-com-lote-e-validade.png','01 | Entrada por lote','Destaque as quatro unidades do LEITE-A e as seis do LEITE-B, cada lote com sua validade.'),
('06-relatorio-validade-e-exportacao.png','06 | Alertas e exportação','Destaque Vencido, A vencer, os filtros, Exportar CSV e Imprimir / salvar PDF.')]),
('Compare o estoque antes e depois',[
('02-estoque-antes-10-unidades.png','02 | Antes: 10 unidades','No leite, o lote A tem 4 e o lote B tem 6. O iogurte vencido tem saldo físico, mas não vendável.'),
('04-estoque-depois-8-unidades.png','04 | Depois: 8 unidades','Após vender 2 unidades, o lote A fica com 2 e o lote B continua com 6: saída pelo primeiro vencimento.')]),
('Venda e bloqueio no caixa',[
('03-pos-venda-de-2-unidades.png','03 | Venda de duas unidades','O carrinho contém duas unidades de leite a R$ 5,50. Total: R$ 11,00.'),
('05-bloqueio-estoque-zerado.png','05 | Teste de estoque zerado','Ao tentar incluir ARROZ pelo código, a interface mostra estoque insuficiente. A API também rejeitou a venda.')])]
for title,items in boards:
    story += [PageBreak(),p(title,'TitleCustom'),p('Capturas reais da aplicação | Dados fictícios | 27/10/2026','Hint')]
    for name,title,caption in items:
        story += [p(title,'SectionCustom'),pic(name),Spacer(1,5),p(caption,'Hint')]
    if 'bloqueio' in title.lower():pass
def footer(c,doc):
    c.setStrokeColor(colors.HexColor('#d9e3ed'));c.line(44,39,551,39)
    c.setFont('Helvetica',8);c.setFillColor(colors.HexColor('#607185'));c.drawString(44,25,'Equipe C | Guia Rodada 2 de ADM2, pp. 12-13 | Projeto conferido em 27/10/2026');c.drawRightString(551,25,str(doc.page))
doc=SimpleDocTemplate(str(out/'roteiro-5-minutos.pdf'),pagesize=(595.28,841.89),rightMargin=44,leftMargin=44,topMargin=38,bottomMargin=48,title='Roteiro de 5 minutos - Inventário RyuTech',author='Equipe C')
doc.build(story,onFirstPage=footer,onLaterPages=footer)
print('PDF e roteiro editável criados.')
