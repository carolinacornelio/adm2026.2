package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** O saldo total inclui vencidos; o saldo vendável exclui vencidos e validade pendente. */
@Service
public class InventarioService {
    public static final String HOJE_SQL = "(CURRENT_TIMESTAMP AT TIME ZONE 'America/Fortaleza')::date";
    public static final String DISPONIVEL_SQL = "GREATEST(0,COALESCE(e.quantidade,0)-COALESCE((SELECT SUM(l.quantidade) FROM estoque_lotes l WHERE l.estoque_id=e.id AND (l.validade<" + HOJE_SQL + " OR (p.controla_validade AND l.validade IS NULL))),0)-CASE WHEN p.controla_validade THEN GREATEST(0,COALESCE(e.quantidade,0)-COALESCE((SELECT SUM(l.quantidade) FROM estoque_lotes l WHERE l.estoque_id=e.id),0)) ELSE 0 END)";
    private final JdbcTemplate db;
    private final PosRealtimeService realtime;
    public InventarioService(JdbcTemplate db, PosRealtimeService realtime) { this.db=db; this.realtime=realtime; }
    public static LocalDate hoje() { return LocalDate.now(ZoneId.of("America/Fortaleza")); }
    public void notificar(int empresa) { realtime.publicarDepoisDoCommit(empresa,"estoque-atualizado"); }

    private Map<String,Object> bloquear(int empresa, int produto) {
        var rows=db.queryForList("SELECT e.id,e.quantidade,p.controla_validade FROM estoque e JOIN produtos p ON p.id=e.produto_id AND p.empresa_id=e.empresa_id WHERE e.empresa_id=? AND e.produto_id=? AND p.ativo=true ORDER BY e.id FOR UPDATE OF e,p",empresa,produto);
        if(rows.size()!=1) throw new IllegalArgumentException("Produto indisponível ou estoque inconsistente.");
        var saldo=rows.get(0);
        int id=((Number)saldo.get("id")).intValue();
        int total=((Number)saldo.get("quantidade")).intValue();
        long lotes=db.queryForObject("SELECT COALESCE(SUM(quantidade),0) FROM estoque_lotes WHERE estoque_id=?",Long.class,id);
        if(lotes>total) throw new IllegalArgumentException("Saldo de lotes inconsistente. Confira o inventário.");
        if(total>lotes) db.update("INSERT INTO estoque_lotes(empresa_id,produto_id,estoque_id,codigo,quantidade,legado) VALUES (?,?,?,'SALDO-SEM-LOTE-'||?::text,?,false)",empresa,produto,id,id,total-lotes);
        return saldo;
    }

    @Transactional
    public void entrada(int empresa,int produto,int quantidade,int compra,String codigo,LocalDate validade) {
        var saldo=bloquear(empresa,produto);
        if(quantidade<=0) throw new IllegalArgumentException("Quantidade inválida.");
        if(((Number)saldo.get("quantidade")).longValue()+quantidade>Integer.MAX_VALUE)
            throw new IllegalArgumentException("A entrada excede o saldo máximo permitido para o produto.");
        codigo=codigo==null?"":codigo.trim();
        if(codigo.length()>100) throw new IllegalArgumentException("O lote deve ter até 100 caracteres.");
        if(Boolean.TRUE.equals(saldo.get("controla_validade")) && (codigo.isBlank() || validade==null))
            throw new IllegalArgumentException("Informe lote e validade para o produto perecível.");
        if(validade!=null && validade.isBefore(hoje())) throw new IllegalArgumentException("Não é permitido receber mercadoria vencida.");
        int estoque=((Number)saldo.get("id")).intValue();
        db.update("INSERT INTO estoque_lotes(empresa_id,produto_id,estoque_id,compra_id,codigo,validade,quantidade) VALUES (?,?,?,?,?,?,?)",empresa,produto,estoque,compra,codigo.isBlank()?"COMPRA-"+compra:codigo,validade,quantidade);
        db.update("UPDATE estoque SET quantidade=quantidade+?,updated_at=CURRENT_TIMESTAMP WHERE id=?",quantidade,estoque);
        db.update("INSERT INTO movimentacoes_estoque(empresa_id,produto_id,tipo,quantidade,motivo) VALUES (?,?,'ENTRADA',?,?)",empresa,produto,quantidade,"Compra #"+compra);
    }

    @Transactional
    public void baixar(int empresa,int produto,int quantidade,int venda) {
        var saldo=bloquear(empresa,produto);
        boolean perecivel=Boolean.TRUE.equals(saldo.get("controla_validade"));
        var lotes=db.queryForList("SELECT id,quantidade FROM estoque_lotes WHERE estoque_id=? AND quantidade>0 AND (validade>=? OR (validade IS NULL AND ?=false)) ORDER BY validade ASC NULLS LAST,id FOR UPDATE",saldo.get("id"),hoje(),perecivel);
        int disponivel=lotes.stream().mapToInt(l->((Number)l.get("quantidade")).intValue()).sum();
        if(quantidade<=0 || quantidade>disponivel) throw new IllegalArgumentException("Estoque vendável insuficiente. Lotes vencidos ou sem validade obrigatória não podem ser vendidos.");
        int restante=quantidade;
        for(var lote:lotes) {
            int usar=Math.min(restante,((Number)lote.get("quantidade")).intValue());
            if(usar==0) break;
            db.update("UPDATE estoque_lotes SET quantidade=quantidade-? WHERE id=?",usar,lote.get("id"));
            db.update("INSERT INTO venda_lotes(venda_id,lote_id,quantidade) VALUES (?,?,?)",venda,lote.get("id"),usar);
            restante-=usar;
        }
        db.update("UPDATE estoque SET quantidade=quantidade-?,updated_at=CURRENT_TIMESTAMP WHERE id=?",quantidade,saldo.get("id"));
        db.update("INSERT INTO movimentacoes_estoque(empresa_id,produto_id,tipo,quantidade,motivo) VALUES (?,?,'SAIDA',?,?)",empresa,produto,quantidade,"Venda #"+venda);
    }

    @Transactional
    public void definirValidade(int empresa,int lote,String codigo,LocalDate validade) {
        var rows=db.queryForList("SELECT produto_id FROM estoque_lotes WHERE id=? AND empresa_id=?",lote,empresa);
        if(rows.isEmpty()) throw new IllegalArgumentException("Lote não encontrado.");
        bloquear(empresa,((Number)rows.get(0).get("produto_id")).intValue());
        if(codigo==null || codigo.isBlank() || codigo.trim().length()>100 || validade==null) throw new IllegalArgumentException("Informe código do lote e validade.");
        // Regularizar um lote vencido é permitido para registrar sua situação real; ele permanece bloqueado para venda.
        if(db.update("UPDATE estoque_lotes SET codigo=?,validade=? WHERE id=? AND empresa_id=? AND validade IS NULL AND quantidade>0",codigo.trim(),validade,lote,empresa)!=1)
            throw new IllegalArgumentException("Somente lotes sem validade e com saldo podem ser regularizados.");
        realtime.publicarDepoisDoCommit(empresa,"estoque-atualizado");
    }

    @Scheduled(cron="0 0 0 * * *",zone="America/Fortaleza")
    public void viradaDoDia() {
        for(Integer empresa:db.queryForList("SELECT id FROM empresas",Integer.class)) realtime.publicarDepoisDoCommit(empresa,"estoque-atualizado");
    }
}
