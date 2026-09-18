package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Centraliza operações que alteram estoque.
 *
 * Compra soma quantidades; venda subtrai. O @Transactional garante rollback
 * se qualquer item falhar, evitando compra/venda gravada pela metade.
 */
@RestController
public class MovimentoController {
 private final JdbcTemplate db;private final CurrentUser user;public MovimentoController(JdbcTemplate db,CurrentUser user){this.db=db;this.user=user;}
 /** Registra venda e dá saída no estoque. */
 @PostMapping("/api/vendas") @Transactional Map<String,Object> venda(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){return movimentar(d,a,true);}
 /** Registra compra e dá entrada no estoque. */
 @PostMapping("/api/compras") @Transactional Map<String,Object> compra(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){return movimentar(d,a,false);}
 @GetMapping("/api/compras") List<Map<String,Object>> compras(@RequestHeader("Authorization")String a){return db.queryForList("SELECT id, total, status, data FROM compras WHERE empresa_id=? ORDER BY id DESC",user.empresa(a));}
 @GetMapping("/api/vendas") List<Map<String,Object>> vendas(@RequestHeader("Authorization")String a){return db.queryForList("SELECT id, total, status, data FROM vendas WHERE empresa_id=? ORDER BY id DESC",user.empresa(a));}
 @GetMapping("/api/estoque") List<Map<String,Object>> estoque(@RequestHeader("Authorization")String a){return db.queryForList("SELECT p.id,p.nome,p.codigo,COALESCE(e.quantidade,0) AS \"quantidadeEstoque\",COALESCE(e.estoque_minimo,0) AS \"estoqueMinimo\",CASE WHEN COALESCE(e.quantidade,0)<=0 THEN 'Esgotado' WHEN COALESCE(e.quantidade,0)<=COALESCE(e.estoque_minimo,0) THEN 'Baixo' ELSE 'OK' END AS situacao FROM produtos p LEFT JOIN estoque e ON e.produto_id=p.id AND e.empresa_id=p.empresa_id WHERE p.empresa_id=? AND p.ativo=true",user.empresa(a));}
 private Map<String,Object> movimentar(Map<String,Object>d,String a,boolean venda){int e=user.empresa(a);Object pessoa=d.get(venda?"clienteId":"fornecedorId");if(pessoa==null)throw new IllegalArgumentException("Informe o "+(venda?"cliente":"fornecedor")+".");int idPessoa=inteiro(pessoa);String pessoaTabela=venda?"clientes":"fornecedores";if(db.queryForObject("SELECT COUNT(*) FROM "+pessoaTabela+" WHERE id=? AND empresa_id=?",Integer.class,idPessoa,e)==0)throw new IllegalArgumentException("Pessoa não encontrada para esta empresa.");List<Map<String,Object>> itens=(List<Map<String,Object>>)d.get("itens");if(itens==null||itens.isEmpty())throw new IllegalArgumentException("Informe ao menos um item.");String tabela=venda?"vendas":"compras",itabela=venda?"itens_venda":"itens_compra",col=venda?"cliente_id":"fornecedor_id";double total=decimal(d.getOrDefault("total",0));Number mov=db.queryForObject("INSERT INTO "+tabela+"(empresa_id,"+col+",usuario_id,total) VALUES(?,?,?,?) RETURNING id",Number.class,e,idPessoa,user.usuario(a),total);for(Map<String,Object>i:itens){int p=inteiro(i.get("produtoId")),q=inteiro(i.get("quantidade"));if(q<=0)throw new IllegalArgumentException("A quantidade deve ser maior que zero.");List<Integer> qs=db.query("SELECT quantidade FROM estoque e JOIN produtos p ON p.id=e.produto_id WHERE e.produto_id=? AND e.empresa_id=? AND p.ativo=true",(rs,n)->rs.getInt(1),p,e);if(qs.isEmpty())throw new IllegalArgumentException("Produto não encontrado para esta empresa.");int atual=qs.get(0);if(venda&&atual<q)throw new IllegalArgumentException("Estoque insuficiente para o produto "+p+".");double preco=decimal(i.getOrDefault("precoUnitario",0));db.update("INSERT INTO "+itabela+"("+(venda?"venda_id":"compra_id")+",produto_id,quantidade,preco_unitario) VALUES(?,?,?,?)",mov,p,q,preco);db.update("UPDATE estoque SET quantidade=quantidade+?,updated_at=NOW() WHERE produto_id=? AND empresa_id=?",venda?-q:q,p,e);}return Map.of("id",mov,"total",total);}
 private int inteiro(Object valor){if(valor==null)throw new IllegalArgumentException("ID inválido.");return valor instanceof Number n?n.intValue():Integer.parseInt(valor.toString());}
 private double decimal(Object valor){if(valor==null)return 0;return valor instanceof Number n?n.doubleValue():Double.parseDouble(valor.toString().replace(",","."));}
}
