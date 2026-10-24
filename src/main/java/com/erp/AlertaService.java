package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AlertaService {
    private final JdbcTemplate db;
    public AlertaService(JdbcTemplate db) { this.db = db; }

    @Transactional
    public void atualizar(int empresa) {
        // Serializa acessos simultâneos da mesma empresa para não duplicar eventos.
        db.queryForList("SELECT pg_advisory_xact_lock(74128, ?)", empresa);
        Set<String> atuais = new HashSet<>();
        var produtos = db.queryForList("SELECT p.id,p.nome,COALESCE(e.estoque_minimo,0) AS minimo, "
                + InventarioService.DISPONIVEL_SQL + " AS saldo FROM produtos p LEFT JOIN estoque e "
                + "ON e.produto_id=p.id AND e.empresa_id=p.empresa_id WHERE p.empresa_id=? AND p.ativo=true", empresa);
        for (var p : produtos) {
            long saldo=((Number)p.get("saldo")).longValue(), minimo=Math.max(0,((Number)p.get("minimo")).longValue());
            if (saldo > EstoqueStatus.limiteProximo(minimo)) continue;
            String nivel=saldo<=minimo ? "ALERTA" : "NORMAL";
            String texto=saldo==0 ? p.get("nome")+": sem estoque vendável."
                    : p.get("nome")+": "+saldo+" unidades vendáveis, "+(saldo<=minimo ? "no mínimo ou abaixo" : "próximo do mínimo")+".";
            registrar(empresa,"ESTOQUE:"+p.get("id")+":"+nivel,"ESTOQUE",nivel,texto+" Mínimo: "+minimo+".",atuais);
        }
        LocalDate hoje=InventarioService.hoje();
        var lotes=db.queryForList("SELECT l.id,l.codigo,l.validade,l.quantidade,p.nome FROM estoque_lotes l JOIN produtos p "
                +"ON p.id=l.produto_id AND p.empresa_id=l.empresa_id WHERE l.empresa_id=? AND p.ativo=true AND l.quantidade>0 "
                +"AND l.validade IS NOT NULL AND l.validade<=? ORDER BY l.validade,l.id",empresa,hoje.plusDays(30));
        for (var l:lotes) {
            LocalDate validade=((java.sql.Date)l.get("validade")).toLocalDate();
            long dias=ChronoUnit.DAYS.between(hoje,validade);
            String nivel=ValidadeStatus.nivelAlerta(dias);
            String prazo=dias<0 ? "vencido há "+(-dias)+" dia(s)" : dias==0 ? "vence hoje" : "vence em "+dias+" dia(s)";
            registrar(empresa,"VALIDADE:"+l.get("id")+":"+validade+":"+nivel,"VALIDADE",nivel,
                    l.get("nome")+" — lote "+l.get("codigo")+": "+prazo+" ("+validade+"). Saldo: "+l.get("quantidade")+".",atuais);
        }
        for (var a:db.queryForList("SELECT id,chave FROM alertas_operacionais WHERE empresa_id=? AND ativo=true",empresa)) {
            if(!atuais.contains(a.get("chave"))) db.update("UPDATE alertas_operacionais SET ativo=false,atualizado_em=CURRENT_TIMESTAMP WHERE id=? AND empresa_id=?",a.get("id"),empresa);
        }
    }

    private void registrar(int empresa,String chave,String tipo,String nivel,String mensagem,Set<String> atuais) {
        atuais.add(chave);
        db.update("INSERT INTO alertas_operacionais(empresa_id,chave,tipo,nivel,mensagem) VALUES (?,?,?,?,?) "
                +"ON CONFLICT (empresa_id,chave) WHERE ativo=true DO UPDATE SET mensagem=EXCLUDED.mensagem,atualizado_em=CURRENT_TIMESTAMP",
                empresa,chave,tipo,nivel,mensagem);
    }

    public Map<String,Object> listar(int empresa,int usuario,int pagina) {
        int offset=Math.min(Math.max(pagina,0),1000000)*50;
        var itens=db.queryForList("SELECT a.id,a.tipo,a.nivel,a.mensagem,a.ativo,a.criado_em, "
                +"(l.alerta_id IS NOT NULL) AS lido FROM alertas_operacionais a LEFT JOIN alertas_operacionais_leituras l "
                +"ON l.alerta_id=a.id AND l.usuario_id=? WHERE a.empresa_id=? AND l.alerta_id IS NOT NULL ORDER BY l.lido_em DESC,a.id DESC LIMIT 50 OFFSET ?",usuario,empresa,offset);
        long total=db.queryForObject("SELECT COUNT(*) FROM alertas_operacionais a JOIN alertas_operacionais_leituras l ON l.alerta_id=a.id AND l.usuario_id=? WHERE a.empresa_id=?",Long.class,usuario,empresa);
        var novos=db.queryForList("SELECT a.id,a.nivel,a.mensagem,a.ativo FROM alertas_operacionais a WHERE a.empresa_id=? "
                +"AND NOT EXISTS(SELECT 1 FROM alertas_operacionais_leituras l WHERE l.alerta_id=a.id AND l.usuario_id=?) "
                +"ORDER BY CASE a.nivel WHEN 'PRIORIDADE' THEN 0 WHEN 'IMPORTANTE' THEN 1 WHEN 'ALERTA' THEN 2 ELSE 3 END,a.id DESC",empresa,usuario);
        return Map.of("itens",itens,"total",total,"novos",novos,"pagina",Math.max(0,pagina));
    }
    @Transactional
    public void ler(int empresa,int usuario,List<Long> ids) {
        if(ids==null || ids.size()>100) throw new IllegalArgumentException("Informe até 100 alertas.");
        for(Long id:ids) if(id!=null) db.update("INSERT INTO alertas_operacionais_leituras(alerta_id,usuario_id) "
                +"SELECT id,? FROM alertas_operacionais WHERE id=? AND empresa_id=? ON CONFLICT DO NOTHING",usuario,id,empresa);
    }
}
