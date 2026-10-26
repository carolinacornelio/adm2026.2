package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * Centraliza operações que alteram estoque.
 *
 * Venda subtrai e compra soma, sempre pelo InventarioService (lotes, validade e
 * segunda verificação do saldo). O @Transactional garante rollback se qualquer item
 * falhar, evitando compra/venda gravada pela metade.
 *
 * Controles de compliance (Lei Anticorrupção):
 * - ETAPA 1.2: rastreabilidade e bloqueio de fornecedores com dados bancários em análise.
 * - ETAPA 2.1: alertas automáticos para valores > R$ 50.000,00 (ALT-01) e serviços sem contrato (ALT-02).
 * - ETAPA 2.2: hierarquia de aprovação com dupla alçada para compras >= R$ 10.000,00;
 *              o estoque dessas compras só entra depois da aprovação final.
 * - ETAPA 1.1: Regra de Ouro SoD (quem solicita ou aprova a compra não liquida o pagamento).
 */
@RestController
public class MovimentoController {
 private static final BigDecimal LIMITE_ALCADA = new BigDecimal("10000.00");
 private final JdbcTemplate db;private final CurrentUser user;private final PosRealtimeService realtime;private final InventarioService inventario;private final ComplianceService compliance;
 public MovimentoController(JdbcTemplate db,CurrentUser user,PosRealtimeService realtime,InventarioService inventario,ComplianceService compliance){this.db=db;this.user=user;this.realtime=realtime;this.inventario=inventario;this.compliance=compliance;}
 /** Registra venda e dá saída no estoque. */
 @PostMapping("/api/vendas") @Transactional public Map<String,Object> venda(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){return movimentar(d,a,true);}
 /** Registra compra e submete ao fluxo de alçadas e compliance. */
 @PostMapping("/api/compras") @Transactional public Map<String,Object> compra(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){return movimentar(d,a,false);}
 @GetMapping("/api/compras") public List<Map<String,Object>> compras(@RequestHeader("Authorization")String a){
  return db.queryForList("""
   SELECT c.id, c.total, c.status, c.data,
          c.aprovador_nivel_1 AS "aprovadorNivel1",
          c.aprovador_nivel_2 AS "aprovadorNivel2",
          c.data_aprovacao_n1 AS "dataAprovacaoN1",
          c.data_aprovacao_n2 AS "dataAprovacaoN2",
          c.nota_fiscal AS "notaFiscal",
          c.tipo_documento AS "tipoDocumento",
          f.nome AS "fornecedorNome",
          u.nome AS "solicitanteNome"
     FROM compras c
     LEFT JOIN fornecedores f ON f.id = c.fornecedor_id AND f.empresa_id = c.empresa_id
     LEFT JOIN usuarios u ON u.id = c.usuario_id
    WHERE c.empresa_id = ?
    ORDER BY c.id DESC
   """, user.empresa(a));
 }
 @GetMapping("/api/vendas")
 List<Map<String,Object>> vendas(@RequestHeader("Authorization") String a,
         @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate data,
         @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate inicio,
         @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fim) {
  if (data != null) { inicio = data; fim = data; }
  if (inicio != null && fim != null && inicio.isAfter(fim))
   throw new IllegalArgumentException("A data inicial deve ser anterior ou igual à data final.");
  StringBuilder sql = new StringBuilder("""
   SELECT v.id, v.total, v.status, v.data, COALESCE(v.origem,'ERP') AS origem,
          COALESCE(c.nome,'Consumidor final') AS cliente,
          v.pos_forma_pagamento AS "formaPagamento"
     FROM vendas v
     LEFT JOIN clientes c ON c.id=v.cliente_id AND c.empresa_id=v.empresa_id
    WHERE v.empresa_id=?
   """);
  List<Object> parametros = new ArrayList<>();
  parametros.add(user.empresa(a));
  if (inicio != null) {
   sql.append(" AND v.data >= ?");
   parametros.add(Timestamp.valueOf(inicio.atStartOfDay()));
  }
  if (fim != null) {
   sql.append(" AND v.data < ?");
   parametros.add(Timestamp.valueOf(fim.plusDays(1).atStartOfDay()));
  }
  sql.append(" ORDER BY v.data DESC, v.id DESC");
  return db.queryForList(sql.toString(), parametros.toArray());
 }
 @GetMapping("/api/vendas/{id}")
 Map<String,Object> detalheVenda(@PathVariable int id, @RequestHeader("Authorization") String a) {
  int empresa = user.empresa(a);
  var vendas = db.queryForList("""
   SELECT v.id, v.total, v.status, v.data, COALESCE(v.origem,'ERP') AS origem,
          COALESCE(c.nome,'Consumidor final') AS cliente, u.nome AS operador,
          (SELECT nome_fantasia FROM empresas WHERE id=v.empresa_id) AS empresa,
          v.pos_valor_recebido AS "valorRecebido",v.pos_troco AS troco,
          v.pos_forma_pagamento AS "formaPagamento", COALESCE(v.pos_desconto,0) AS desconto
     FROM vendas v
     LEFT JOIN clientes c ON c.id=v.cliente_id AND c.empresa_id=v.empresa_id
     LEFT JOIN usuarios u ON u.id=v.usuario_id AND u.empresa_id=v.empresa_id
    WHERE v.id=? AND v.empresa_id=?
   """, id, empresa);
  if (vendas.isEmpty()) throw new IllegalArgumentException("Venda não encontrada para esta empresa.");
  var resultado = new LinkedHashMap<>(vendas.get(0));
  resultado.put("itens", db.queryForList("""
   SELECT COALESCE(i.pos_nome_produto,p.nome,'Produto removido') AS nome,
          COALESCE(i.pos_codigo_produto,p.codigo,'') AS codigo,
          i.quantidade, i.preco_unitario AS "precoUnitario",
          i.quantidade*i.preco_unitario AS subtotal
     FROM itens_venda i
     JOIN vendas v ON v.id=i.venda_id
     LEFT JOIN produtos p ON p.id=i.produto_id AND p.empresa_id=v.empresa_id
    WHERE i.venda_id=? AND v.empresa_id=? ORDER BY i.id
   """, id, empresa));
  resultado.put("lotes",db.queryForList("SELECT l.codigo,l.validade,vl.quantidade,p.nome FROM venda_lotes vl JOIN vendas v ON v.id=vl.venda_id JOIN estoque_lotes l ON l.id=vl.lote_id JOIN produtos p ON p.id=l.produto_id WHERE v.id=? AND v.empresa_id=? ORDER BY vl.id",id,empresa));
  return resultado;
 }
 @GetMapping("/api/estoque")
 List<Map<String,Object>> estoque(@RequestHeader("Authorization") String a) {
  return ProdutoValidade.aplicar(db,user.empresa(a),db.queryForList("SELECT p.id,p.nome,p.codigo,p.controla_validade AS \"controlaValidade\",e.quantidade AS \"quantidadeTotal\","+InventarioService.DISPONIVEL_SQL+" AS \"quantidadeEstoque\",e.estoque_minimo AS \"estoqueMinimo\" FROM produtos p JOIN estoque e ON e.produto_id=p.id AND e.empresa_id=p.empresa_id WHERE p.empresa_id=? AND p.ativo=true ORDER BY p.nome",user.empresa(a)));
 }

 /** ETAPA 2.2 & ETAPA 3.1: hierarquia de aprovação dinâmica e bloqueio de perfil operacional. */
 @PostMapping("/api/compras/{id}/aprovar")
 @Transactional
 public Map<String,Object> aprovarCompra(@PathVariable int id, @RequestHeader("Authorization") String a) {
  int e = user.empresa(a);
  int usuarioId = user.usuario(a);
  String perfil = user.perfil(a);
  List<Map<String,Object>> rows = db.queryForList(
   "SELECT id, total, status, aprovador_nivel_1, usuario_id FROM compras WHERE id = ? AND empresa_id = ? FOR UPDATE", id, e);
  if (rows.isEmpty()) throw new IllegalArgumentException("Compra não encontrada.");
  Map<String,Object> compra = rows.get(0);
  double total = ((Number) compra.get("total")).doubleValue();
  String status = String.valueOf(compra.get("status"));
  // Só compras pendentes podem ser aprovadas: evita dar entrada no estoque duas vezes.
  if (!status.equals("PENDENTE_APROVACAO") && !status.equals("AGUARDANDO_NIVEL_2"))
   throw new IllegalArgumentException("Esta compra não está aguardando aprovação (situação atual: " + status + ").");
  Integer n1 = (Integer) compra.get("aprovador_nivel_1");
  Integer solicitanteId = (Integer) compra.get("usuario_id");

  // ETAPA 3: bloqueio de usuário operacional tentando aprovar unilateralmente compras >= 10k
  if (total >= 10000.0 && (perfil.equals("compras_operador") || perfil.equals("comprador")
          || perfil.equals("operador") || perfil.equals("padrao") || perfil.contains("usuario_compras"))) {
   compliance.registrarAlerta(e, usuarioId, "COMPRA_ID_" + id, total,
           "TENTATIVA_ELEVACAO_PRIVILEGIO", "CRITICA",
           "Usuário com perfil restrito de compras tentou aprovar unilateralmente pedido de alto valor sem alçada gerencial.");
   throw new ComplianceException("COMPLIANCE_ALCADA_VIOLATION",
           "Violação de Compliance: O usuário atual não possui alçada ou nível hierárquico suficiente para aprovar transações acima de R$ 10.000,00.");
  }

  if (n1 == null) {
   // Nível 1: gerente ou administrador
   if (!perfil.equals("gerente_compras") && !perfil.equals("admin") && !perfil.equals("gerente") && !perfil.equals("diretor"))
    throw new ComplianceException("COMPLIANCE_ALCADA_VIOLATION", "Apenas gerentes ou administradores possuem alçada de Nível 1 para aprovação.");
   if (total < 10000.0) {
    db.update("UPDATE compras SET aprovador_nivel_1 = ?, data_aprovacao_n1 = NOW(), status = 'CONCLUIDA' WHERE id = ? AND empresa_id = ?", usuarioId, id, e);
    efetivarEntradaEstoqueEContasPagar(id, e);
    return Map.of("id", id, "status", "CONCLUIDA", "mensagem", "Compra aprovada em alçada única e estoque atualizado.");
   }
   db.update("UPDATE compras SET aprovador_nivel_1 = ?, data_aprovacao_n1 = NOW(), status = 'AGUARDANDO_NIVEL_2' WHERE id = ? AND empresa_id = ?", usuarioId, id, e);
   return Map.of("id", id, "status", "AGUARDANDO_NIVEL_2", "mensagem", "Aprovação Nível 1 registrada. Aguardando aprovação de segundo nível (Diretor/Compliance).");
  }
  // Nível 2: usuário distinto do primeiro aprovador e do solicitante
  if (n1 == usuarioId || (solicitanteId != null && solicitanteId == usuarioId))
   throw new ComplianceException("COMPLIANCE_ALCADA_VIOLATION",
           "Violação de Alçada: O segundo aprovador deve ser um usuário distinto do primeiro aprovador e do solicitante.");
  if (!perfil.equals("diretor") && !perfil.equals("compliance") && !perfil.equals("admin"))
   throw new ComplianceException("COMPLIANCE_ALCADA_VIOLATION", "A aprovação de Nível 2 exige alçada de Diretor, Compliance ou Administrador.");
  db.update("UPDATE compras SET aprovador_nivel_2 = ?, data_aprovacao_n2 = NOW(), status = 'CONCLUIDA' WHERE id = ? AND empresa_id = ?", usuarioId, id, e);
  efetivarEntradaEstoqueEContasPagar(id, e);
  return Map.of("id", id, "status", "CONCLUIDA", "mensagem", "Aprovação de alçada dupla concluída com sucesso. Estoque atualizado.");
 }

 /** ETAPA 1.1: processamento de pagamento aplicando a Regra de Ouro SoD. */
 @PostMapping("/api/compras/{id}/pagar")
 @Transactional
 public Map<String,Object> pagarCompra(@PathVariable int id, @RequestHeader("Authorization") String a) {
  int e = user.empresa(a);
  int usuarioId = user.usuario(a);
  var rows = db.queryForList("SELECT status FROM compras WHERE id = ? AND empresa_id = ? FOR UPDATE", id, e);
  if (rows.isEmpty()) throw new IllegalArgumentException("Compra não encontrada.");
  if (!"CONCLUIDA".equals(String.valueOf(rows.get(0).get("status"))))
   throw new IllegalArgumentException("Somente compras concluídas e ainda não pagas podem ser liquidadas.");
  compliance.validarRegraDeOuroSoD(e, id, usuarioId);
  db.update("UPDATE compras SET status = 'PAGO' WHERE id = ? AND empresa_id = ?", id, e);
  db.update("UPDATE contas_pagar SET status = 'pago', data_pagamento = CURRENT_DATE, usuario_liquidacao_id = ? WHERE compra_id = ? AND empresa_id = ?",
          usuarioId, id, e);
  return Map.of("id", id, "status", "PAGO", "mensagem", "Pagamento liquidado com sucesso respeitando a segregação de funções.");
 }

 private Map<String,Object> movimentar(Map<String,Object>d,String a,boolean venda){
  int e=user.empresa(a);int usuarioId=user.usuario(a);Object pessoa=d.get(venda?"clienteId":"fornecedorId");
  if(pessoa==null)throw new IllegalArgumentException("Informe o "+(venda?"cliente":"fornecedor")+".");
  int idPessoa=inteiro(pessoa);String pessoaTabela=venda?"clientes":"fornecedores";
  if(db.queryForObject("SELECT COUNT(*) FROM "+pessoaTabela+" WHERE id=? AND empresa_id=?",Integer.class,idPessoa,e)==0)throw new IllegalArgumentException("Pessoa não encontrada para esta empresa.");
  // ETAPA 1.3: fornecedor com dados bancários em análise fica com movimentações suspensas.
  if(!venda && Boolean.TRUE.equals(db.queryForObject("SELECT COALESCE(dados_bancarios_bloqueados,false) FROM fornecedores WHERE id=? AND empresa_id=?",Boolean.class,idPessoa,e)))
   throw new ComplianceException("COMPLIANCE_FORNECEDOR_BLOQUEADO","Fornecedor com dados bancários em análise preventiva. Movimentações suspensas até validação de compliance.");
  List<?> itens = itens(d.get("itens"));
  if(itens.size()>200 || itens.stream().anyMatch(item -> !(item instanceof Map<?,?>))) throw new IllegalArgumentException("Informe até 200 itens válidos.");
  var ordem=new ArrayList<>(itens);
  ordem.sort(Comparator.comparingInt(item->inteiro(((Map<?,?>)item).get("produtoId"))));
  itens=ordem;
  LocalDate data = data(d.get("data"));
  String tipoDoc = String.valueOf(d.getOrDefault("tipoDocumento", "MERCADORIA")).trim().toUpperCase(Locale.ROOT);
  if(!venda && !Set.of("MERCADORIA","SERVICO").contains(tipoDoc)) throw new IllegalArgumentException("Tipo de documento inválido.");
  String notaFiscal = String.valueOf(d.getOrDefault("notaFiscal", "")).trim();
  if(notaFiscal.length()>100) throw new IllegalArgumentException("A nota fiscal deve ter até 100 caracteres.");
  Integer contratoId = d.get("contratoId") != null && !d.get("contratoId").toString().isBlank() ? inteiro(d.get("contratoId")) : null;
  Integer mov = venda
   ? db.queryForObject("INSERT INTO vendas(empresa_id,cliente_id,usuario_id,total,data) VALUES(?,?,?,0,?) RETURNING id",Integer.class,e,idPessoa,usuarioId,data)
   : db.queryForObject("INSERT INTO compras(empresa_id,fornecedor_id,usuario_id,total,data,status,tipo_documento,nota_fiscal,contrato_id) VALUES(?,?,?,0,?,'PENDENTE_CALCULO',?,?,?) RETURNING id",
       Integer.class,e,idPessoa,usuarioId,data,tipoDoc,notaFiscal,contratoId);
  BigDecimal total=BigDecimal.ZERO;
  String colunaPreco = venda ? "COALESCE(p.preco_venda, p.preco)" : "COALESCE(p.preco_compra, p.preco)";
  for(Object item:itens){
   if (!(item instanceof Map<?, ?> bruto)) throw new IllegalArgumentException("Item da movimentação inválido.");
   Map<String,Object> i = new HashMap<>();
   bruto.forEach((chave, valor) -> i.put(String.valueOf(chave), valor));
   int p=inteiro(i.get("produtoId")),q=inteiro(i.get("quantidade"));
   if(q<=0 || q>1000000)throw new IllegalArgumentException("A quantidade deve ser maior que zero.");
   List<Map<String,Object>> produto=db.queryForList("SELECT e.quantidade,p.nome,p.codigo,"+colunaPreco+" AS preco FROM estoque e JOIN produtos p ON p.id=e.produto_id AND p.empresa_id=e.empresa_id WHERE e.produto_id=? AND e.empresa_id=? AND p.ativo=true ORDER BY e.id FOR UPDATE OF e,p",p,e);
   if(produto.size()!=1)throw new IllegalArgumentException("Produto não encontrado ou estoque inconsistente para esta empresa.");
   int atual=((Number)produto.get(0).get("quantidade")).intValue();
   if(venda&&atual<q)throw new IllegalArgumentException("Estoque insuficiente para o produto "+p+".");
   // O preço é sempre o que está cadastrado no produto; o navegador não decide o valor da compra.
   BigDecimal preco=(BigDecimal)produto.get(0).get("preco");
   total=total.add(preco.multiply(BigDecimal.valueOf(q)));
   if(preco.signum()<0 || total.compareTo(new BigDecimal("99999999.99"))>0)throw new IllegalArgumentException("O total excede o valor máximo permitido para uma movimentação.");
   if(venda) {
    db.update("INSERT INTO itens_venda(venda_id,produto_id,quantidade,preco_unitario,pos_nome_produto,pos_codigo_produto) VALUES(?,?,?,?,?,?)",mov,p,q,preco,produto.get(0).get("nome"),produto.get(0).get("codigo"));
    inventario.baixar(e,p,q,mov.intValue());
   } else {
    String lote=Objects.toString(i.get("lote"),"").trim();
    LocalDate validade=i.get("validade")==null || i.get("validade").toString().isBlank()?null:data(i.get("validade"));
    // Valida já na criação, mesmo quando a entrada no estoque depender de aprovação.
    inventario.validarEntrada(e,p,q,lote,validade);
    db.update("INSERT INTO itens_compra(compra_id,produto_id,quantidade,preco_unitario,lote,validade) VALUES(?,?,?,?,?,?)",mov,p,q,preco,lote.isBlank()?null:lote,validade);
   }
  }
  String status;
  if(venda) {
   status="concluida";
   db.update("INSERT INTO contas_receber (empresa_id, venda_id, descricao, valor, vencimento, status) VALUES (?, ?, ?, ?, ?, 'pendente')",
           e, mov, "Venda #" + mov, total, data.plusDays(30));
  } else {
   // ETAPA 2.1: nota fiscal de serviço exige contrato homologado (ALT-02).
   if("SERVICO".equals(tipoDoc)) compliance.validarNotaFiscalServico(e, usuarioId, total.doubleValue(), contratoId);
   // ETAPA 2.1: alerta automático ALT-01 para transações > R$ 50.000,00.
   compliance.verificarAlertaValorExcessivo(e, usuarioId, "COMPRA_ID_" + mov, total.doubleValue());
   // ETAPA 2.2: compras >= R$ 10.000,00 entram em alçada; as demais dão entrada imediata.
   status = total.compareTo(LIMITE_ALCADA) >= 0 ? "PENDENTE_APROVACAO" : "CONCLUIDA";
  }
  db.update("UPDATE "+(venda?"vendas":"compras")+" SET total=?,status=? WHERE id=? AND empresa_id=?",total,status,mov,e);
  if(!venda && status.equals("CONCLUIDA")) efetivarEntradaEstoqueEContasPagar(mov, e);
  realtime.publicarDepoisDoCommit(e,"estoque-atualizado");
  return Map.of("id",mov,"total",total,"status",status);
 }

 /** Dá entrada dos itens da compra pelos lotes (com segunda verificação) e gera a conta a pagar. */
 private void efetivarEntradaEstoqueEContasPagar(int compraId, int empresaId) {
  var itens = db.queryForList("SELECT produto_id, quantidade, lote, validade FROM itens_compra WHERE compra_id = ? ORDER BY produto_id, id", compraId);
  for (Map<String,Object> item : itens) {
   Object validade = item.get("validade");
   LocalDate data = validade == null ? null : validade instanceof java.sql.Date sql ? sql.toLocalDate() : LocalDate.parse(validade.toString());
   inventario.entrada(empresaId, ((Number) item.get("produto_id")).intValue(), ((Number) item.get("quantidade")).intValue(),
           compraId, Objects.toString(item.get("lote"), ""), data);
  }
  BigDecimal total = db.queryForObject("SELECT total FROM compras WHERE id = ? AND empresa_id = ?", BigDecimal.class, compraId, empresaId);
  if (db.queryForObject("SELECT COUNT(*) FROM contas_pagar WHERE compra_id = ?", Integer.class, compraId) == 0)
   db.update("INSERT INTO contas_pagar (empresa_id, compra_id, descricao, valor, vencimento, status) VALUES (?, ?, ?, ?, CURRENT_DATE + 30, 'pendente')",
           empresaId, compraId, "Compra #" + compraId, total);
  realtime.publicarDepoisDoCommit(empresaId,"estoque-atualizado");
 }
 private int inteiro(Object valor){try {return new BigDecimal(String.valueOf(valor)).intValueExact();} catch(RuntimeException ex) {throw new IllegalArgumentException("Informe um número inteiro válido.");}}
 private List<?> itens(Object valor) {
  if (!(valor instanceof List<?> itens) || itens.isEmpty()) throw new IllegalArgumentException("Informe ao menos um item.");
  return itens;
 }
 private LocalDate data(Object valor) {
  if (valor == null || valor.toString().isBlank()) return InventarioService.hoje();
  try { return LocalDate.parse(valor.toString()); }
  catch (RuntimeException erro) { throw new IllegalArgumentException("Data inválida. Use o formato AAAA-MM-DD."); }
 }
}
