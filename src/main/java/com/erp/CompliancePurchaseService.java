package com.erp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aprovações mantêm itens, preço e lotes originais; a entrada acontece uma única vez. */
@Service
public class CompliancePurchaseService {
    private static final BigDecimal ALCADA=new BigDecimal("10000.00");
    private final JdbcTemplate db;
    private final InventarioService inventario;
    private final PosRealtimeService realtime;
    private final ComplianceService compliance;
    public CompliancePurchaseService(JdbcTemplate db,InventarioService inventario,PosRealtimeService realtime,ComplianceService compliance){this.db=db;this.inventario=inventario;this.realtime=realtime;this.compliance=compliance;}

    private void fornecedor(int empresa,int usuario,int id){
        var linhas=db.queryForList("SELECT dados_bancarios_bloqueados FROM fornecedores WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
        if(linhas.isEmpty())throw new IllegalArgumentException("Fornecedor não encontrado para esta empresa.");
        if(Boolean.TRUE.equals(linhas.get(0).get("dados_bancarios_bloqueados")))compliance.bloquear(empresa,usuario,"FORNECEDOR_"+id,BigDecimal.ZERO,"COMPLIANCE_FORNECEDOR_BLOQUEADO","O fornecedor possui alteração bancária pendente. Aguarde a revisão antes de comprar, aprovar ou pagar.",HttpStatus.CONFLICT);
    }
    private void contrato(int empresa,int usuario,int fornecedor,Integer contrato,BigDecimal valor,LocalDate data,boolean servico,boolean reservar){
        if(contrato==null){
            if(servico)compliance.bloquear(empresa,usuario,"NF_SERVICO",valor,"COMPLIANCE_CONTRATO_OBRIGATORIO","Serviços exigem contrato aprovado do mesmo fornecedor.",HttpStatus.BAD_REQUEST);
            return;
        }
        var rows=db.queryForList("SELECT fornecedor_id,status,valor_total,data_inicio,data_fim FROM contratos_fornecedores WHERE id=? AND empresa_id=? FOR UPDATE",contrato,empresa);
        if(rows.isEmpty()||((Number)rows.get(0).get("fornecedor_id")).intValue()!=fornecedor)throw new IllegalArgumentException("Contrato não encontrado para este fornecedor e empresa.");
        var c=rows.get(0);
        if(!"APROVADO".equals(c.get("status")))compliance.bloquear(empresa,usuario,"CONTRATO_"+contrato,valor,"COMPLIANCE_CONTRATO_PENDENTE","O contrato precisa ser homologado antes de ser utilizado.",HttpStatus.CONFLICT);
        LocalDate inicio=ComplianceValues.data(c.get("data_inicio"),null),fim=ComplianceValues.data(c.get("data_fim"),null);
        if(inicio!=null&&data.isBefore(inicio)||fim!=null&&data.isAfter(fim))throw new IllegalArgumentException("A data da compra está fora da vigência do contrato.");
        if(reservar){
            BigDecimal utilizado=db.queryForObject("SELECT COALESCE(SUM(total),0) FROM compras WHERE empresa_id=? AND contrato_id=? AND LOWER(status)<>'cancelada'",BigDecimal.class,empresa,contrato);
            if(utilizado.add(valor).compareTo((BigDecimal)c.get("valor_total"))>0)throw new IllegalArgumentException("O valor acumulado das compras excede o valor do contrato.");
        }
    }

    @Transactional
    public Map<String,Object> registrar(int empresa,int usuario,Map<String,Object> dados){
        int fornecedor=ComplianceValues.inteiro(dados.get("fornecedorId"));
        fornecedor(empresa,usuario,fornecedor);
        String tipo=ComplianceValues.texto(dados,"tipoDocumento",20,false).toUpperCase(Locale.ROOT);
        if(tipo.isEmpty())tipo="MERCADORIA";
        if(!Set.of("MERCADORIA","SERVICO").contains(tipo))throw new IllegalArgumentException("Tipo de documento inválido.");
        boolean servico=tipo.equals("SERVICO");
        LocalDate data=ComplianceValues.data(dados.get("data"),InventarioService.hoje());
        String nf=ComplianceValues.texto(dados,"notaFiscal",100,servico),objeto=ComplianceValues.texto(dados,"descricaoServico",255,servico);
        Integer contrato=dados.get("contratoId")==null||dados.get("contratoId").toString().isBlank()?null:ComplianceValues.inteiro(dados.get("contratoId"));
        BigDecimal total=BigDecimal.ZERO.setScale(2);
        List<Map<String,Object>> itens=new ArrayList<>();
        if(servico){
            if(dados.get("itens") instanceof List<?> linhas&&!linhas.isEmpty())throw new IllegalArgumentException("Serviços não recebem itens de estoque.");
            total=ComplianceValues.dinheiro(dados.get("valorServico"),false);
        }else{
            if(!(dados.get("itens") instanceof List<?> linhas)||linhas.isEmpty()||linhas.size()>200||linhas.stream().anyMatch(x->!(x instanceof Map<?,?>)))throw new IllegalArgumentException("Informe de 1 a 200 itens válidos.");
            var ordenados=new ArrayList<>(linhas);
            ordenados.sort(Comparator.comparingInt(x->ComplianceValues.inteiro(((Map<?,?>)x).get("produtoId"))));
            Map<Integer,Long> quantidades=new HashMap<>();
            for(var raw:ordenados){
                Map<String,Object> item=new HashMap<>();((Map<?,?>)raw).forEach((k,v)->item.put(String.valueOf(k),v));
                int produto=ComplianceValues.inteiro(item.get("produtoId")),quantidade=ComplianceValues.inteiro(item.get("quantidade"));
                if(quantidade>1000000)throw new IllegalArgumentException("Quantidade máxima por item excedida.");
                var rows=db.queryForList("SELECT p.controla_validade,COALESCE(p.preco_compra,p.preco) AS preco,e.quantidade FROM produtos p JOIN estoque e ON e.produto_id=p.id AND e.empresa_id=p.empresa_id WHERE p.id=? AND p.empresa_id=? AND p.ativo=true ORDER BY e.id FOR UPDATE OF e,p",produto,empresa);
                if(rows.size()!=1)throw new IllegalArgumentException("Produto não encontrado ou estoque inconsistente para esta empresa.");
                var p=rows.get(0);
                long soma=quantidades.merge(produto,(long)quantidade,Long::sum);
                if(soma+((Number)p.get("quantidade")).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("A entrada excede o saldo máximo do produto.");
                String lote=ComplianceValues.texto(item,"lote",100,false);
                LocalDate validade=ComplianceValues.data(item.get("validade"),null);
                if(Boolean.TRUE.equals(p.get("controla_validade"))&&(lote.isBlank()||validade==null))throw new IllegalArgumentException("Informe lote e validade para o produto perecível.");
                if(validade!=null&&validade.isBefore(InventarioService.hoje()))throw new IllegalArgumentException("Não é permitido receber mercadoria vencida.");
                BigDecimal preco=ComplianceValues.dinheiro(p.get("preco"),true);
                total=total.add(preco.multiply(BigDecimal.valueOf(quantidade)));
                if(total.compareTo(ComplianceValues.LIMITE)>0)throw new IllegalArgumentException("O total excede o valor máximo da compra.");
                var persistir=new HashMap<String,Object>();persistir.put("produto",produto);persistir.put("quantidade",quantidade);persistir.put("preco",preco);persistir.put("lote",lote);persistir.put("validade",validade);itens.add(persistir);
            }
        }
        // Contratos são bloqueados antes dos produtos em aprovações. O cadastro já serializa o fornecedor.
        contrato(empresa,usuario,fornecedor,contrato,total,data,servico,true);
        int id=db.queryForObject("INSERT INTO compras(empresa_id,fornecedor_id,usuario_id,total,data,status,tipo_documento,nota_fiscal,contrato_id,objeto_servico,entrada_efetivada) VALUES (?,?,?,?,?,'PENDENTE_APROVACAO',?,?,?,?,false) RETURNING id",Integer.class,empresa,fornecedor,usuario,total,data.atStartOfDay(ZoneId.of("America/Fortaleza")).toOffsetDateTime(),tipo,nf,contrato,objeto);
        for(var item:itens)db.update("INSERT INTO itens_compra(compra_id,produto_id,quantidade,preco_unitario,lote_codigo,validade) VALUES (?,?,?,?,?,?)",id,item.get("produto"),item.get("quantidade"),item.get("preco"),item.get("lote"),item.get("validade"));
        String status="PENDENTE_APROVACAO";
        if(total.compareTo(ALCADA)<0){efetivar(empresa,id);status="concluida";}
        compliance.verificarValor(empresa,usuario,"COMPRA_"+id,total);
        return Map.of("id",id,"total",total,"status",status,"tipoDocumento",tipo);
    }

    private Map<String,Object> bloquearCompra(int empresa,int usuario,int id){
        var rows=db.queryForList("SELECT fornecedor_id,contrato_id FROM compras WHERE id=? AND empresa_id=?",id,empresa);
        if(rows.isEmpty())throw new IllegalArgumentException("Compra não encontrada para esta empresa.");
        fornecedor(empresa,usuario,((Number)rows.get(0).get("fornecedor_id")).intValue());
        if(rows.get(0).get("contrato_id")!=null)db.queryForList("SELECT id FROM contratos_fornecedores WHERE id=? AND empresa_id=? FOR UPDATE",rows.get(0).get("contrato_id"),empresa);
        return db.queryForMap("SELECT *, (data AT TIME ZONE 'America/Fortaleza')::date AS dia_comercial FROM compras WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
    }
    private void efetivar(int empresa,int id){
        var compra=db.queryForMap("SELECT total,tipo_documento,entrada_efetivada,(data AT TIME ZONE 'America/Fortaleza')::date AS dia FROM compras WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
        if(Boolean.TRUE.equals(compra.get("entrada_efetivada")))return;
        if(!"SERVICO".equals(compra.get("tipo_documento"))){
            var itens=db.queryForList("SELECT produto_id,quantidade,lote_codigo,validade FROM itens_compra WHERE compra_id=? ORDER BY produto_id,id",id);
            if(itens.isEmpty())throw new IllegalArgumentException("Compra sem itens de mercadoria.");
            for(var item:itens)inventario.entrada(empresa,((Number)item.get("produto_id")).intValue(),((Number)item.get("quantidade")).intValue(),id,String.valueOf(item.get("lote_codigo")),ComplianceValues.data(item.get("validade"),null));
            realtime.publicarDepoisDoCommit(empresa,"estoque-atualizado");
        }
        LocalDate dia=ComplianceValues.data(compra.get("dia"),InventarioService.hoje());
        db.update("INSERT INTO contas_pagar(empresa_id,compra_id,descricao,valor,vencimento,status) SELECT ?,?,?,?,?,'pendente' WHERE NOT EXISTS(SELECT 1 FROM contas_pagar WHERE empresa_id=? AND compra_id=?)",empresa,id,"Compra #"+id,compra.get("total"),dia.plusDays(30),empresa,id);
        db.update("UPDATE compras SET entrada_efetivada=true,status='concluida' WHERE id=? AND empresa_id=?",id,empresa);
    }
    @Transactional
    public Map<String,Object> aprovar(int empresa,int usuario,String perfil,int id){
        var compra=bloquearCompra(empresa,usuario,id);
        if(!ComplianceService.nivelUm(perfil)&&!ComplianceService.revisor(perfil))compliance.bloquear(empresa,usuario,"COMPRA_"+id,(BigDecimal)compra.get("total"),"COMPLIANCE_ALCADA","Seu perfil não possui alçada para aprovar compras.",HttpStatus.FORBIDDEN);
        String status=String.valueOf(compra.get("status"));
        if(Set.of("concluida","paga").contains(status.toLowerCase(Locale.ROOT)))return Map.of("id",id,"status",status);
        if(!Set.of("PENDENTE_APROVACAO","AGUARDANDO_NIVEL_2").contains(status))throw new IllegalArgumentException("A compra não está aguardando aprovação.");
        BigDecimal valor=(BigDecimal)compra.get("total");
        if(((Number)compra.get("usuario_id")).intValue()==usuario)compliance.bloquear(empresa,usuario,"COMPRA_"+id,valor,"COMPLIANCE_SOD_APROVACAO","O solicitante não pode aprovar a própria compra.",HttpStatus.FORBIDDEN);
        if(status.equals("PENDENTE_APROVACAO")){
            if(!ComplianceService.nivelUm(perfil))compliance.bloquear(empresa,usuario,"COMPRA_"+id,valor,"COMPLIANCE_ALCADA_N1","A primeira aprovação exige Gerente de Compras, Diretor ou Administrador.",HttpStatus.FORBIDDEN);
            db.update("UPDATE compras SET aprovador_nivel_1=?,data_aprovacao_n1=CURRENT_TIMESTAMP,status='AGUARDANDO_NIVEL_2' WHERE id=? AND empresa_id=?",usuario,id,empresa);
            return Map.of("id",id,"status","AGUARDANDO_NIVEL_2");
        }
        if(!status.equals("AGUARDANDO_NIVEL_2"))throw new IllegalArgumentException("A compra não está aguardando aprovação.");
        if(!ComplianceService.revisor(perfil)||compra.get("aprovador_nivel_1")==null||((Number)compra.get("aprovador_nivel_1")).intValue()==usuario)compliance.bloquear(empresa,usuario,"COMPRA_"+id,valor,"COMPLIANCE_ALCADA_N2","A segunda aprovação exige outro Diretor, Compliance ou Administrador, diferente do primeiro aprovador e do solicitante.",HttpStatus.FORBIDDEN);
        contrato(empresa,usuario,((Number)compra.get("fornecedor_id")).intValue(),compra.get("contrato_id")==null?null:((Number)compra.get("contrato_id")).intValue(),valor,ComplianceValues.data(compra.get("dia_comercial"),InventarioService.hoje()),"SERVICO".equals(compra.get("tipo_documento")),false);
        db.update("UPDATE compras SET aprovador_nivel_2=?,data_aprovacao_n2=CURRENT_TIMESTAMP WHERE id=? AND empresa_id=?",usuario,id,empresa);
        efetivar(empresa,id);
        return Map.of("id",id,"status","concluida");
    }
    @Transactional
    public Map<String,Object> pagar(int empresa,int usuario,String perfil,int id){
        ComplianceService.exigir(ComplianceService.financeiro(perfil));
        var compra=bloquearCompra(empresa,usuario,id);
        if("paga".equalsIgnoreCase(String.valueOf(compra.get("status"))))return Map.of("id",id,"status","paga");
        if(!"concluida".equalsIgnoreCase(String.valueOf(compra.get("status")))||!Boolean.TRUE.equals(compra.get("entrada_efetivada")))throw new IllegalArgumentException("O pagamento exige uma compra concluída.");
        for(String campo:List.of("usuario_id","aprovador_nivel_1","aprovador_nivel_2"))if(compra.get(campo) instanceof Number n&&n.intValue()==usuario)compliance.bloquear(empresa,usuario,"COMPRA_"+id,(BigDecimal)compra.get("total"),"COMPLIANCE_SOD_PAGAMENTO","Quem solicitou ou aprovou a compra não pode liquidar seu pagamento.",HttpStatus.FORBIDDEN);
        var titulos=db.queryForList("SELECT id,status FROM contas_pagar WHERE empresa_id=? AND compra_id=? FOR UPDATE",empresa,id);
        if(titulos.size()!=1||!"pendente".equals(titulos.get(0).get("status")))throw new IllegalArgumentException("Título financeiro ausente ou inconsistente. Revise a conta antes de pagar.");
        db.update("UPDATE contas_pagar SET status='pago',data_pagamento=?,usuario_liquidacao_id=? WHERE id=? AND empresa_id=?",InventarioService.hoje(),usuario,titulos.get(0).get("id"),empresa);
        db.update("UPDATE compras SET status='paga' WHERE id=? AND empresa_id=?",id,empresa);
        return Map.of("id",id,"status","paga");
    }
    @Transactional
    public Map<String,Object> cancelar(int empresa,int usuario,String perfil,int id){
        var linhas=db.queryForList("SELECT usuario_id,status FROM compras WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
        if(linhas.isEmpty())throw new IllegalArgumentException("Compra não encontrada.");
        ComplianceService.exigir(perfil.equals("admin")||((Number)linhas.get(0).get("usuario_id")).intValue()==usuario);
        if(!Set.of("PENDENTE_APROVACAO","AGUARDANDO_NIVEL_2").contains(String.valueOf(linhas.get(0).get("status"))))throw new IllegalArgumentException("Somente compras pendentes podem ser canceladas sem alterar o estoque.");
        db.update("UPDATE compras SET status='cancelada' WHERE id=? AND empresa_id=?",id,empresa);
        return Map.of("id",id,"status","cancelada");
    }
    public List<Map<String,Object>> listar(int empresa){
        return db.queryForList("SELECT c.id,c.total,c.status,c.data,c.tipo_documento AS \"tipoDocumento\",c.nota_fiscal AS \"notaFiscal\",c.objeto_servico AS \"descricaoServico\",c.usuario_id AS \"solicitanteId\",c.aprovador_nivel_1 AS \"aprovadorNivel1\",c.aprovador_nivel_2 AS \"aprovadorNivel2\",f.nome AS \"fornecedorNome\",u.nome AS \"solicitanteNome\",(SELECT MIN(p.status) FROM contas_pagar p WHERE p.compra_id=c.id AND p.empresa_id=c.empresa_id) AS \"contaStatus\" FROM compras c JOIN fornecedores f ON f.id=c.fornecedor_id AND f.empresa_id=c.empresa_id LEFT JOIN usuarios u ON u.id=c.usuario_id AND u.empresa_id=c.empresa_id WHERE c.empresa_id=? ORDER BY c.id DESC LIMIT 500",empresa);
    }
    public Map<String,Object> detalhe(int empresa,int id){
        var linhas=db.queryForList("SELECT id,total,status,tipo_documento AS \"tipoDocumento\",objeto_servico AS \"descricaoServico\" FROM compras WHERE id=? AND empresa_id=?",id,empresa);
        if(linhas.isEmpty())throw new IllegalArgumentException("Compra não encontrada.");
        var resultado=linhas.get(0);
        resultado.put("itens",db.queryForList("SELECT p.nome,i.quantidade,i.preco_unitario AS \"precoUnitario\",i.lote_codigo AS lote,i.validade FROM itens_compra i JOIN compras c ON c.id=i.compra_id JOIN produtos p ON p.id=i.produto_id AND p.empresa_id=c.empresa_id WHERE i.compra_id=? AND c.empresa_id=? ORDER BY i.id",id,empresa));
        return resultado;
    }
}
