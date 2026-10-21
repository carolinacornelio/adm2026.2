package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * Centraliza operações que alteram estoque.
 *
 * Compra soma quantidades; venda subtrai. O @Transactional garante rollback
 * se qualquer item falhar, evitando compra/venda gravada pela metade.
 */
@RestController
public class MovimentoController {
 private final JdbcTemplate db;private final CurrentUser user;private final PosRealtimeService realtime;private final InventarioService inventario;public MovimentoController(JdbcTemplate db,CurrentUser user,PosRealtimeService realtime,InventarioService inventario){this.db=db;this.user=user;this.realtime=realtime;this.inventario=inventario;}
 /** Registra venda e dá saída no estoque. */
 @PostMapping("/api/vendas") @Transactional Map<String,Object> venda(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){return movimentar(d,a,true);}
 /** Registra compra e dá entrada no estoque. */
 @PostMapping("/api/compras") @Transactional Map<String,Object> compra(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){return movimentar(d,a,false);}
 @GetMapping("/api/compras") List<Map<String,Object>> compras(@RequestHeader("Authorization")String a){return db.queryForList("SELECT id, total, status, data FROM compras WHERE empresa_id=? ORDER BY id DESC",user.empresa(a));}
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
 private Map<String,Object> movimentar(Map<String,Object>d,String a,boolean venda){
  int e=user.empresa(a);Object pessoa=d.get(venda?"clienteId":"fornecedorId");
  if(pessoa==null)throw new IllegalArgumentException("Informe o "+(venda?"cliente":"fornecedor")+".");
  int idPessoa=inteiro(pessoa);String pessoaTabela=venda?"clientes":"fornecedores";
  if(db.queryForObject("SELECT COUNT(*) FROM "+pessoaTabela+" WHERE id=? AND empresa_id=?",Integer.class,idPessoa,e)==0)throw new IllegalArgumentException("Pessoa não encontrada para esta empresa.");
  List<?> itens = itens(d.get("itens"));
  if(itens.size()>200 || itens.stream().anyMatch(item -> !(item instanceof Map<?,?>))) throw new IllegalArgumentException("Informe até 200 itens válidos.");
  var ordem=new ArrayList<>(itens);
  ordem.sort(Comparator.comparingInt(item->inteiro(((Map<?,?>)item).get("produtoId"))));
  itens=ordem;
  String tabela=venda?"vendas":"compras",itabela=venda?"itens_venda":"itens_compra",col=venda?"cliente_id":"fornecedor_id";
  LocalDate data = data(d.get("data"));
  Integer mov=db.queryForObject("INSERT INTO "+tabela+"(empresa_id,"+col+",usuario_id,total,data) VALUES(?,?,?,0,?) RETURNING id",Integer.class,e,idPessoa,user.usuario(a),data);
  java.math.BigDecimal total=java.math.BigDecimal.ZERO;
  String colunaPreco = venda ? "COALESCE(p.preco_venda, p.preco)" : "COALESCE(p.preco_compra, p.preco)";
  for(Object item:itens){
   if (!(item instanceof Map<?, ?> bruto)) throw new IllegalArgumentException("Item da movimentação inválido.");
   Map<String,Object> i = new HashMap<>();
   bruto.forEach((chave, valor) -> i.put(String.valueOf(chave), valor));
   int p=inteiro(i.get("produtoId")),q=inteiro(i.get("quantidade"));
   if(q<=0 || q>1000000)throw new IllegalArgumentException("A quantidade deve ser maior que zero.");
   List<Map<String,Object>> produto=db.queryForList("SELECT e.quantidade,p.nome,p.codigo,"+colunaPreco+" AS preco FROM estoque e JOIN produtos p ON p.id=e.produto_id WHERE e.produto_id=? AND e.empresa_id=? AND p.ativo=true FOR UPDATE OF e",p,e);
   if(produto.isEmpty())throw new IllegalArgumentException("Produto não encontrado para esta empresa.");
   int atual=((Number)produto.get(0).get("quantidade")).intValue();
   if(venda&&atual<q)throw new IllegalArgumentException("Estoque insuficiente para o produto "+p+".");
   // O preço é sempre o que está cadastrado no produto; o navegador não decide o valor da compra.
   java.math.BigDecimal preco=(java.math.BigDecimal)produto.get(0).get("preco");
   total=total.add(preco.multiply(java.math.BigDecimal.valueOf(q)));
   if(venda) {
    db.update("INSERT INTO itens_venda(venda_id,produto_id,quantidade,preco_unitario,pos_nome_produto,pos_codigo_produto) VALUES(?,?,?,?,?,?)",mov,p,q,preco,produto.get(0).get("nome"),produto.get(0).get("codigo"));
    inventario.baixar(e,p,q,mov.intValue());
   } else {
    db.update("INSERT INTO itens_compra(compra_id,produto_id,quantidade,preco_unitario) VALUES(?,?,?,?)",mov,p,q,preco);
    LocalDate validade=i.get("validade")==null || i.get("validade").toString().isBlank()?null:data(i.get("validade"));
    inventario.entrada(e,p,q,mov.intValue(),String.valueOf(i.getOrDefault("lote","")),validade);
   }
  }
  db.update("UPDATE "+tabela+" SET total=?,status='concluida' WHERE id=? AND empresa_id=?",total,mov,e);
  realtime.publicarDepoisDoCommit(e,"estoque-atualizado");
  return Map.of("id",mov,"total",total);
 }
 private int inteiro(Object valor){try {return new java.math.BigDecimal(String.valueOf(valor)).intValueExact();} catch(RuntimeException ex) {throw new IllegalArgumentException("Informe um número inteiro válido.");}}
 private List<?> itens(Object valor) {
  if (!(valor instanceof List<?> itens) || itens.isEmpty()) throw new IllegalArgumentException("Informe ao menos um item.");
  return itens;
 }
 private LocalDate data(Object valor) {
  if (valor == null || valor.toString().isBlank()) return LocalDate.now();
  try { return LocalDate.parse(valor.toString()); }
  catch (RuntimeException erro) { throw new IllegalArgumentException("Data inválida. Use o formato AAAA-MM-DD."); }
 }
}
