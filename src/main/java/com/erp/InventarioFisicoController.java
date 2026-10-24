package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Contagem física separada do inventário por lote/validade. Todas as consultas são isoladas por empresa. */
@RestController
@RequestMapping("/api/inventarios-fisicos")
public class InventarioFisicoController {
    private final JdbcTemplate db;
    private final CurrentUser user;
    private final InventarioService inventario;

    public InventarioFisicoController(JdbcTemplate db, CurrentUser user, InventarioService inventario) {
        this.db = db; this.user = user; this.inventario = inventario;
    }

    @GetMapping
    public List<Map<String,Object>> listar(@RequestHeader("Authorization") String auth) {
        return db.queryForList("""
            SELECT i.id, i.status, i.observacao, i.criado_em AS "criadoEm", i.concluido_em AS "concluidoEm",
                   u.nome AS responsavel,
                   (SELECT COUNT(*) FROM inventario_fisico_itens x WHERE x.inventario_id=i.id) AS "totalItens",
                   (SELECT COUNT(*) FROM inventario_fisico_itens x WHERE x.inventario_id=i.id AND x.quantidade_contada IS NOT NULL) AS "itensContados",
                   (SELECT COUNT(*) FROM inventario_fisico_itens x WHERE x.inventario_id=i.id AND x.quantidade_contada IS NOT NULL AND x.quantidade_contada<>x.quantidade_sistema) AS "divergencias"
              FROM inventarios_fisicos i JOIN usuarios u ON u.id=i.usuario_id
             WHERE i.empresa_id=? ORDER BY i.id DESC LIMIT 50
            """, user.empresa(auth));
    }

    @PostMapping
    @Transactional
    public Map<String,Object> abrir(@RequestHeader("Authorization") String auth, @RequestBody(required=false) Map<String,Object> body) {
        int empresa=user.empresa(auth), usuario=user.usuario(auth);
        String observacao=body==null?"":String.valueOf(body.getOrDefault("observacao", "")).trim();
        if(observacao.length()>500) throw new IllegalArgumentException("A observação deve ter até 500 caracteres.");
        Integer id=db.queryForObject("INSERT INTO inventarios_fisicos(empresa_id,usuario_id,status,observacao) VALUES (?,?,'EM_CONTAGEM',?) RETURNING id",Integer.class,empresa,usuario,observacao);
        db.update("""
            INSERT INTO inventario_fisico_itens(inventario_id,empresa_id,produto_id,quantidade_sistema,estoque_atualizado_em,preco_unitario)
            SELECT ?, e.empresa_id, p.id, e.quantidade, e.updated_at, COALESCE(NULLIF(p.preco_venda,0),p.preco,0)
              FROM produtos p JOIN estoque e ON e.produto_id=p.id AND e.empresa_id=p.empresa_id
             WHERE p.empresa_id=? AND p.ativo=true ORDER BY p.nome,p.id
            """,id,empresa);
        return obterInterno(empresa,id);
    }

    @GetMapping("/{id}")
    public Map<String,Object> obter(@PathVariable int id,@RequestHeader("Authorization") String auth) {
        return obterInterno(user.empresa(auth),id);
    }

    private Map<String,Object> obterInterno(int empresa,int id) {
        var cab=db.queryForList("""
            SELECT i.id,i.status,i.observacao,i.criado_em AS "criadoEm",i.concluido_em AS "concluidoEm",u.nome AS responsavel
              FROM inventarios_fisicos i JOIN usuarios u ON u.id=i.usuario_id WHERE i.id=? AND i.empresa_id=?
            """,id,empresa);
        if(cab.isEmpty()) throw new IllegalArgumentException("Inventário físico não encontrado.");
        var itens=db.queryForList("""
            SELECT x.id AS "itemId",p.id AS "produtoId",p.nome,p.codigo, x.quantidade_sistema AS "quantidadeSistema",
                   x.quantidade_contada AS "quantidadeContada",(x.quantidade_contada-x.quantidade_sistema) AS diferenca,
                   x.preco_unitario AS "precoUnitario",(x.quantidade_contada-x.quantidade_sistema)*x.preco_unitario AS "valorDiferenca",
                   x.observacao, CASE WHEN x.quantidade_contada IS NULL THEN 'NAO_CONTADO'
                     WHEN x.quantidade_contada=x.quantidade_sistema THEN 'CONFERIDO'
                     WHEN x.quantidade_contada<x.quantidade_sistema THEN 'FALTA' ELSE 'SOBRA' END AS situacao
              FROM inventario_fisico_itens x JOIN produtos p ON p.id=x.produto_id AND p.empresa_id=x.empresa_id
             WHERE x.inventario_id=? AND x.empresa_id=? ORDER BY p.nome,p.id
            """,id,empresa);
        Map<String,Object> result=new LinkedHashMap<>(cab.get(0));
        result.put("itens",itens);
        result.put("totalProdutos",itens.size());
        result.put("itensContados",itens.stream().filter(x->x.get("quantidadeContada")!=null).count());
        result.put("divergencias",itens.stream().filter(x->x.get("diferenca")!=null && ((Number)x.get("diferenca")).intValue()!=0).count());
        result.put("unidadesSistema",itens.stream().mapToLong(x->((Number)x.get("quantidadeSistema")).longValue()).sum());
        result.put("unidadesContadas",itens.stream().filter(x->x.get("quantidadeContada")!=null).mapToLong(x->((Number)x.get("quantidadeContada")).longValue()).sum());
        result.put("valorDiferenca",itens.stream().filter(x->x.get("valorDiferenca")!=null).mapToDouble(x->Math.abs(((Number)x.get("valorDiferenca")).doubleValue())).sum());
        return result;
    }

    @PutMapping("/{id}/itens/{itemId}")
    @Transactional
    public Map<String,Object> contar(@PathVariable int id,@PathVariable int itemId,@RequestHeader("Authorization") String auth,@RequestBody Map<String,Object> body) {
        int empresa=user.empresa(auth), usuario=user.usuario(auth);
        var sessao=db.queryForList("SELECT status FROM inventarios_fisicos WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
        if(sessao.isEmpty() || !"EM_CONTAGEM".equals(sessao.get(0).get("status")))
            throw new IllegalArgumentException("Inventário não encontrado ou já finalizado.");
        Object raw=body.get("quantidadeContada");
        if(!(raw instanceof Number n) || n.doubleValue()<0 || n.doubleValue()!=Math.floor(n.doubleValue()) || n.doubleValue()>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Informe uma quantidade física inteira igual ou maior que zero.");
        String observacao=String.valueOf(body.getOrDefault("observacao", "")).trim();
        if(observacao.length()>500) throw new IllegalArgumentException("A observação deve ter até 500 caracteres.");
        if(db.update("""
            UPDATE inventario_fisico_itens x SET quantidade_contada=?,observacao=?,contado_em=CURRENT_TIMESTAMP,contado_por=?
              FROM inventarios_fisicos i WHERE x.id=? AND x.inventario_id=i.id AND x.inventario_id=? AND x.empresa_id=? AND i.empresa_id=? AND i.status='EM_CONTAGEM'
            """,n.intValue(),observacao,usuario,itemId,id,empresa,empresa)!=1) throw new IllegalArgumentException("Item não encontrado ou inventário não está aberto para contagem.");
        return obterInterno(empresa,id);
    }

    @PostMapping("/{id}/aprovar")
    @Transactional
    public Map<String,Object> aprovar(@PathVariable int id,@RequestHeader("Authorization") String auth,@RequestBody(required=false) Map<String,Object> body) {
        int empresa=user.empresa(auth), usuario=user.usuario(auth);
        var cab=db.queryForList("SELECT status FROM inventarios_fisicos WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
        if(cab.isEmpty()) throw new IllegalArgumentException("Inventário físico não encontrado.");
        if(!"EM_CONTAGEM".equals(cab.get(0).get("status"))) throw new IllegalArgumentException("Este inventário já foi finalizado.");
        int total=db.queryForObject("SELECT COUNT(*) FROM inventario_fisico_itens WHERE inventario_id=? AND empresa_id=?",Integer.class,id,empresa);
        if(total==0) throw new IllegalArgumentException("Não é possível aprovar um inventário sem produtos.");
        int pendentes=db.queryForObject("SELECT COUNT(*) FROM inventario_fisico_itens WHERE inventario_id=? AND empresa_id=? AND quantidade_contada IS NULL",Integer.class,id,empresa);
        if(pendentes>0) throw new IllegalArgumentException("Ainda existem produtos sem contagem. Registre inclusive zero para os produtos sem unidades físicas.");
        var itens=db.queryForList("""
            SELECT x.id AS item_id,x.produto_id,x.quantidade_sistema,x.quantidade_contada,x.estoque_atualizado_em,
                   e.id AS estoque_id,e.quantidade AS saldo_atual,e.updated_at AS atualizado_atual,p.controla_validade,p.ativo
              FROM inventario_fisico_itens x JOIN estoque e ON e.produto_id=x.produto_id AND e.empresa_id=x.empresa_id
              JOIN produtos p ON p.id=x.produto_id AND p.empresa_id=x.empresa_id
             WHERE x.inventario_id=? AND x.empresa_id=? ORDER BY p.id,e.id FOR UPDATE OF e,p
            """,id,empresa);
        if(itens.size()!=total || itens.stream().anyMatch(x->!Boolean.TRUE.equals(x.get("ativo"))))
            throw new IllegalArgumentException("Produtos ou estoques foram alterados. Abra uma nova contagem.");
        // Se houve compra/venda/ajuste após a abertura, não se aplica uma contagem potencialmente desatualizada.
        for(var item:itens) {
            Object snap=item.get("estoque_atualizado_em"), atual=item.get("atualizado_atual");
            if((((Number)item.get("saldo_atual")).intValue()!=((Number)item.get("quantidade_sistema")).intValue()) || (snap!=null && atual!=null && !snap.equals(atual))) throw new IllegalArgumentException("O estoque mudou durante a contagem. Para evitar um ajuste incorreto, abra um novo inventário e reconte os produtos afetados.");
        }
        for(var item:itens) {
            int sistema=((Number)item.get("quantidade_sistema")).intValue();
            int contado=((Number)item.get("quantidade_contada")).intValue();
            int delta=contado-sistema;
            if(delta==0) continue;
            int produto=((Number)item.get("produto_id")).intValue();
            int estoque=((Number)item.get("estoque_id")).intValue();
            long saldoLotes=db.queryForObject("SELECT COALESCE(SUM(quantidade),0) FROM estoque_lotes WHERE estoque_id=? AND empresa_id=?",Long.class,estoque,empresa);
            if(saldoLotes!=sistema) throw new IllegalArgumentException("Os lotes não correspondem ao saldo do produto " + produto + ". Corrija a consistência antes de aprovar.");
            if(delta>0) {
                // A sobra é registrada como lote de ajuste sem validade inventada; perecíveis ficam bloqueados até regularização.
                Integer loteId=db.queryForObject("INSERT INTO estoque_lotes(empresa_id,produto_id,estoque_id,codigo,quantidade,legado) VALUES (?,?,?, ?,?,false) RETURNING id",Integer.class,empresa,produto,estoque,"AJUSTE-INV-"+id+"-"+produto,delta);
                db.update("INSERT INTO inventario_fisico_ajuste_lotes(inventario_id,item_id,empresa_id,lote_id,quantidade,tipo) VALUES (?,?,?,?,?,'SOBRA')",id,item.get("item_id"),empresa,loteId,delta);
            } else {
                int restante=-delta;
                var lotes=db.queryForList("SELECT id,quantidade FROM estoque_lotes WHERE estoque_id=? AND quantidade>0 ORDER BY validade ASC NULLS LAST,id FOR UPDATE",estoque);
                for(var lote:lotes) {
                    int saldo=((Number)lote.get("quantidade")).intValue();
                    int usar=Math.min(restante,saldo);
                    if(usar>0) {
                        db.update("UPDATE estoque_lotes SET quantidade=quantidade-? WHERE id=?",usar,lote.get("id"));
                        db.update("INSERT INTO inventario_fisico_ajuste_lotes(inventario_id,item_id,empresa_id,lote_id,quantidade,tipo) VALUES (?,?,?,?,?,'FALTA')",id,item.get("item_id"),empresa,lote.get("id"),usar);
                        restante-=usar;
                    }
                    if(restante==0) break;
                }
                if(restante>0) throw new IllegalArgumentException("Os lotes não correspondem ao saldo do produto " + produto + ". Corrija a consistência dos lotes antes de aprovar.");
            }
            db.update("UPDATE estoque SET quantidade=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND empresa_id=?",contado,estoque,empresa);
            db.update("INSERT INTO movimentacoes_estoque(empresa_id,produto_id,tipo,quantidade,motivo) VALUES (?,?,'AJUSTE',?,?)",empresa,produto,Math.abs(delta),"Inventário físico #"+id+"; ajuste "+(delta>0?"positivo":"negativo")+"; usuário #"+usuario);
        }
        db.update("UPDATE inventarios_fisicos SET status='CONCLUIDO',concluido_em=CURRENT_TIMESTAMP,aprovado_por=? WHERE id=? AND empresa_id=?",usuario,id,empresa);
        inventario.notificar(empresa);
        return obterInterno(empresa,id);
    }
}
