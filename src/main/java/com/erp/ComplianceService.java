package com.erp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Contratos, dupla conferência bancária, riscos e gestão dos papéis da empresa. */
@Service
public class ComplianceService {
    static final BigDecimal LIMIAR_RISCO=new BigDecimal("50000.00");
    static final Set<String> PERFIS=Set.of("admin","gerente_compras","diretor","compliance","financeiro","comprador","padrao");
    private final JdbcTemplate db;
    private final ComplianceAuditService auditoria;
    private final AuthService auth;
    public ComplianceService(JdbcTemplate db,ComplianceAuditService auditoria,AuthService auth){this.db=db;this.auditoria=auditoria;this.auth=auth;}
    public static String normalizarPerfil(String valor){
        String perfil=Objects.toString(valor,"padrao").trim().toLowerCase(Locale.ROOT);
        return switch(perfil){case "administrador"->"admin";case "gerente"->"gerente_compras";case "compras_operador","operador_compras","usuario_compras"->"comprador";default->perfil;};
    }
    static boolean revisor(String perfil){return Set.of("admin","diretor","compliance").contains(perfil);}
    static boolean nivelUm(String perfil){return Set.of("admin","diretor","gerente_compras").contains(perfil);}
    static boolean financeiro(String perfil){return Set.of("admin","diretor","financeiro").contains(perfil);}
    static boolean bancario(String perfil){return revisor(perfil)||Set.of("financeiro","gerente_compras").contains(perfil);}
    static boolean comprador(String perfil){return perfil.contains("comprador")||perfil.contains("compras")&&!perfil.equals("gerente_compras");}
    static void exigir(boolean permitido){if(!permitido)throw new ComplianceException("COMPLIANCE_SEM_PERMISSAO","Seu perfil não permite esta operação.",HttpStatus.FORBIDDEN);}
    void bloquear(int empresa,int usuario,String referencia,BigDecimal valor,String regra,String mensagem,HttpStatus status){
        auditoria.registrarBloqueio(empresa,usuario,referencia,valor,regra,"ALTA",mensagem);
        throw new ComplianceException(regra,mensagem,status);
    }
    void verificarValor(int empresa,int usuario,String referencia,BigDecimal valor){
        if(valor.compareTo(LIMIAR_RISCO)>0)auditoria.registrar(empresa,usuario,referencia,valor,"VALOR_SUPERIOR_50000","CRITICA","Valor superior ao limite interno de acompanhamento de R$ 50.000,00.");
    }
    void validarFornecedorCadastro(int empresa,int usuario,String perfil){
        if(comprador(perfil))bloquear(empresa,usuario,"CADASTRO_FORNECEDOR",BigDecimal.ZERO,"COMPLIANCE_SOD_FORNECEDOR","O perfil de comprador não pode cadastrar ou homologar fornecedores.",HttpStatus.FORBIDDEN);
    }
    public Map<String,Object> contexto(int usuario,String perfil){
        return Map.of("usuarioId",usuario,"perfil",perfil,"podeAuditar",revisor(perfil),"podeHomologar",revisor(perfil),"podeNivel1",nivelUm(perfil),"podeNivel2",revisor(perfil),"podePagar",financeiro(perfil),"podeGerenciar",perfil.equals("admin"),"podeSolicitarBanco",bancario(perfil),"podeCriarContrato",revisor(perfil)||nivelUm(perfil));
    }
    public List<Map<String,Object>> fornecedores(int empresa,String perfil){
        var linhas=db.queryForList("SELECT id,nome,documento,contrato_social_url AS \"contratoSocialUrl\",banco,agencia,conta,chave_pix AS \"chavePix\",dados_bancarios_bloqueados AS \"dadosBancariosBloqueados\" FROM fornecedores WHERE empresa_id=? ORDER BY nome,id",empresa);
        if(!bancario(perfil))for(var linha:linhas)for(String campo:List.of("banco","agencia","conta","chavePix","contratoSocialUrl"))linha.remove(campo);
        return linhas;
    }
    public List<Map<String,Object>> contratos(int empresa){
        return db.queryForList("SELECT c.id,c.fornecedor_id AS \"fornecedorId\",f.nome AS \"fornecedorNome\",c.numero_contrato AS \"numeroContrato\",c.objeto,c.valor_total AS \"valorTotal\",c.status,c.arquivo_url AS \"arquivoUrl\",c.data_inicio AS \"dataInicio\",c.data_fim AS \"dataFim\",c.criado_por AS \"criadoPor\",c.aprovador_id AS \"aprovadorId\",c.created_at AS \"criadoEm\" FROM contratos_fornecedores c JOIN fornecedores f ON f.id=c.fornecedor_id AND f.empresa_id=c.empresa_id WHERE c.empresa_id=? ORDER BY c.id DESC LIMIT 500",empresa);
    }
    @Transactional
    public Map<String,Object> criarContrato(int empresa,int usuario,String perfil,Map<String,Object> dados){
        exigir(revisor(perfil)||nivelUm(perfil));
        int fornecedor=ComplianceValues.inteiro(dados.get("fornecedorId"));
        if(db.queryForObject("SELECT COUNT(*) FROM fornecedores WHERE id=? AND empresa_id=?",Integer.class,fornecedor,empresa)!=1)throw new IllegalArgumentException("Fornecedor não encontrado para esta empresa.");
        String numero=ComplianceValues.texto(dados,"numeroContrato",50,true),objeto=ComplianceValues.texto(dados,"objeto",255,true),url=ComplianceValues.url(dados,"arquivoUrl",true);
        BigDecimal valor=ComplianceValues.dinheiro(dados.get("valorTotal"),false);
        LocalDate inicio=ComplianceValues.data(dados.get("dataInicio"),null),fim=ComplianceValues.data(dados.get("dataFim"),null);
        if(inicio!=null&&fim!=null&&fim.isBefore(inicio))throw new IllegalArgumentException("O fim do contrato deve ser posterior ao início.");
        String status=valor.compareTo(LIMIAR_RISCO)>0?"PENDENTE_COMPLIANCE":"APROVADO";
        int id=db.queryForObject("INSERT INTO contratos_fornecedores(empresa_id,fornecedor_id,numero_contrato,objeto,valor_total,status,arquivo_url,data_inicio,data_fim,criado_por) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",Integer.class,empresa,fornecedor,numero,objeto,valor,status,url,inicio,fim,usuario);
        verificarValor(empresa,usuario,"CONTRATO_"+id,valor);
        return Map.of("id",id,"status",status);
    }
    @Transactional
    public Map<String,Object> homologar(int empresa,int usuario,String perfil,int id){
        exigir(revisor(perfil));
        var linhas=db.queryForList("SELECT criado_por,status,valor_total FROM contratos_fornecedores WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
        if(linhas.isEmpty())throw new IllegalArgumentException("Contrato não encontrado para esta empresa.");
        var contrato=linhas.get(0);
        if("APROVADO".equals(contrato.get("status")))return Map.of("id",id,"status","APROVADO");
        if(((Number)contrato.get("criado_por")).intValue()==usuario)bloquear(empresa,usuario,"CONTRATO_"+id,(BigDecimal)contrato.get("valor_total"),"COMPLIANCE_SOD_CONTRATO","A homologação exige outra pessoa com perfil de Compliance, Diretor ou Administrador.",HttpStatus.FORBIDDEN);
        if(!"PENDENTE_COMPLIANCE".equals(contrato.get("status")))throw new IllegalArgumentException("O contrato não está aguardando homologação.");
        db.update("UPDATE contratos_fornecedores SET status='APROVADO',aprovador_id=? WHERE id=? AND empresa_id=?",usuario,id,empresa);
        return Map.of("id",id,"status","APROVADO");
    }
    @Transactional
    public Map<String,Object> solicitarBanco(int empresa,int usuario,String perfil,int fornecedor,Map<String,Object> dados){
        exigir(bancario(perfil));
        var linhas=db.queryForList("SELECT id,banco,agencia,conta,chave_pix FROM fornecedores WHERE id=? AND empresa_id=? FOR UPDATE",fornecedor,empresa);
        if(linhas.isEmpty())throw new IllegalArgumentException("Fornecedor não encontrado para esta empresa.");
        if(db.queryForObject("SELECT COUNT(*) FROM fornecedores_alteracoes_pendentes WHERE empresa_id=? AND fornecedor_id=? AND status='PENDENTE'",Integer.class,empresa,fornecedor)>0)throw new IllegalArgumentException("Já existe uma solicitação pendente para este fornecedor.");
        String banco=ComplianceValues.texto(dados,"banco",50,false),agencia=ComplianceValues.texto(dados,"agencia",20,false),conta=ComplianceValues.texto(dados,"conta",30,false),pix=ComplianceValues.texto(dados,"chavePix",100,false),url=ComplianceValues.url(dados,"arquivoComprobatorioUrl",true);
        if(pix.isBlank()&&(banco.isBlank()||agencia.isBlank()||conta.isBlank()))throw new IllegalArgumentException("Informe uma chave Pix ou banco, agência e conta completos.");
        var atual=linhas.get(0);
        if(banco.equals(Objects.toString(atual.get("banco"),""))&&agencia.equals(Objects.toString(atual.get("agencia"),""))&&conta.equals(Objects.toString(atual.get("conta"),""))&&pix.equals(Objects.toString(atual.get("chave_pix"),"")))throw new IllegalArgumentException("Os dados bancários informados já estão em uso.");
        int id=db.queryForObject("INSERT INTO fornecedores_alteracoes_pendentes(empresa_id,fornecedor_id,solicitante_id,banco,agencia,conta,chave_pix,arquivo_comprobatorio_url) VALUES (?,?,?,?,?,?,?,?) RETURNING id",Integer.class,empresa,fornecedor,usuario,banco,agencia,conta,pix,url);
        db.update("UPDATE fornecedores SET dados_bancarios_bloqueados=true WHERE id=? AND empresa_id=?",fornecedor,empresa);
        auditoria.registrar(empresa,usuario,"FORNECEDOR_"+fornecedor,BigDecimal.ZERO,"ALTERACAO_BANCARIA_PENDENTE","MEDIA","Alteração bancária aguardando revisão de outra pessoa; compras e pagamentos do fornecedor estão suspensos.");
        return Map.of("id",id,"status","PENDENTE");
    }
    public List<Map<String,Object>> alteracoes(int empresa,int usuario,String perfil){
        if(!bancario(perfil))return List.of();
        return db.queryForList("SELECT p.id,p.fornecedor_id AS \"fornecedorId\",f.nome AS \"fornecedorNome\",p.solicitante_id AS \"solicitanteId\",u.nome AS \"solicitanteNome\",p.banco,p.agencia,p.conta,p.chave_pix AS \"chavePix\",p.arquivo_comprobatorio_url AS \"arquivoComprobatorioUrl\",p.data_solicitacao AS \"dataSolicitacao\",p.status FROM fornecedores_alteracoes_pendentes p JOIN fornecedores f ON f.id=p.fornecedor_id AND f.empresa_id=p.empresa_id JOIN usuarios u ON u.id=p.solicitante_id AND u.empresa_id=p.empresa_id WHERE p.empresa_id=? AND p.status='PENDENTE'"+(!revisor(perfil)?" AND p.solicitante_id=?":"")+" ORDER BY p.id DESC",revisor(perfil)?new Object[]{empresa}:new Object[]{empresa,usuario});
    }
    @Transactional
    public Map<String,Object> decidirBanco(int empresa,int usuario,String perfil,int id,boolean aprovar){
        exigir(revisor(perfil));
        // A mesma ordem de locks usada ao criar solicitações e compras evita decisões concorrentes.
        var ids=db.queryForList("SELECT fornecedor_id FROM fornecedores_alteracoes_pendentes WHERE id=? AND empresa_id=?",id,empresa);
        if(ids.isEmpty())throw new IllegalArgumentException("Solicitação não encontrada para esta empresa.");
        int fornecedor=((Number)ids.get(0).get("fornecedor_id")).intValue();
        db.queryForList("SELECT id FROM fornecedores WHERE id=? AND empresa_id=? FOR UPDATE",fornecedor,empresa);
        var pedido=db.queryForMap("SELECT * FROM fornecedores_alteracoes_pendentes WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
        if(!"PENDENTE".equals(pedido.get("status")))throw new IllegalArgumentException("A solicitação já foi decidida.");
        if(((Number)pedido.get("solicitante_id")).intValue()==usuario)bloquear(empresa,usuario,"ALTERACAO_BANCARIA_"+id,BigDecimal.ZERO,"COMPLIANCE_QUATRO_OLHOS","Quem solicita uma alteração bancária não pode aprová-la ou rejeitá-la.",HttpStatus.FORBIDDEN);
        if(aprovar)db.update("UPDATE fornecedores SET banco=?,agencia=?,conta=?,chave_pix=? WHERE id=? AND empresa_id=?",pedido.get("banco"),pedido.get("agencia"),pedido.get("conta"),pedido.get("chave_pix"),fornecedor,empresa);
        String status=aprovar?"APROVADO":"REJEITADO";
        db.update("UPDATE fornecedores_alteracoes_pendentes SET status=?,aprovador_id=?,data_decisao=CURRENT_TIMESTAMP WHERE id=? AND empresa_id=?",status,usuario,id,empresa);
        db.update("UPDATE fornecedores SET dados_bancarios_bloqueados=EXISTS(SELECT 1 FROM fornecedores_alteracoes_pendentes WHERE empresa_id=? AND fornecedor_id=? AND status='PENDENTE') WHERE id=? AND empresa_id=?",empresa,fornecedor,fornecedor,empresa);
        return Map.of("id",id,"status",status);
    }
    public List<Map<String,Object>> alertas(int empresa,String perfil){
        exigir(revisor(perfil));
        return db.queryForList("SELECT a.id,a.documento_referencia AS \"documentoReferencia\",a.valor_transacao AS \"valorTransacao\",a.motivo_alerta AS \"motivoAlerta\",a.nivel_severidade AS \"nivelSeveridade\",a.detalhes,a.status,a.data_hora AS \"dataHora\",a.justificativa,COALESCE(u.nome,'Sistema') AS \"usuarioNome\" FROM compliance_alertas a LEFT JOIN usuarios u ON u.id=a.usuario_id AND u.empresa_id=a.empresa_id WHERE a.empresa_id=? ORDER BY a.id DESC LIMIT 500",empresa);
    }
    @Transactional
    public Map<String,Object> revisarAlerta(int empresa,int usuario,String perfil,int id,Map<String,Object> dados){
        exigir(revisor(perfil));
        String justificativa=ComplianceValues.texto(dados,"justificativa",500,true);
        if(db.update("UPDATE compliance_alertas SET status='REVISADO',resolvido_por=?,resolvido_em=CURRENT_TIMESTAMP,justificativa=? WHERE id=? AND empresa_id=? AND status='PENDENTE'",usuario,justificativa,id,empresa)!=1)throw new IllegalArgumentException("Alerta não encontrado ou já revisado.");
        return Map.of("id",id,"status","REVISADO");
    }
    public List<Map<String,Object>> usuarios(int empresa,String perfil){
        exigir(revisor(perfil));
        return db.queryForList("SELECT id,nome,email,COALESCE(perfil,'padrao') AS perfil FROM usuarios WHERE empresa_id=? ORDER BY id",empresa);
    }
    public List<Map<String,Object>> auditar(int empresa,String perfil){
        var usuarios=usuarios(empresa,perfil);
        for(var usuario:usuarios){
            String papel=normalizarPerfil(String.valueOf(usuario.get("perfil")));
            boolean conflito=(papel.contains("compras")||papel.contains("comprador"))&&papel.contains("financeiro");
            String risco=conflito?"CONFLITO_SOD":papel.equals("admin")?"PRIVILEGIO_AMPLO":PERFIS.contains(papel)?"SEM_CONFLITO_IDENTIFICADO":"PERFIL_NAO_RECONHECIDO";
            usuario.put("tipoRisco",risco);
            usuario.put("diagnostico",conflito?"Compras e liquidação financeira concentradas no mesmo perfil.":papel.equals("admin")?"Administrador possui permissões amplas; ações continuam exigindo pessoas distintas.":PERFIS.contains(papel)?"Nenhum conflito identificado no perfil; revisar atribuições periodicamente.":"Revise este perfil e atribua um dos papéis disponíveis.");
        }
        return usuarios;
    }
    @Transactional
    public Map<String,Object> criarUsuario(int empresa,int usuario,String perfil,Map<String,Object> dados){
        exigir(perfil.equals("admin"));
        String papel=normalizarPerfil(ComplianceValues.texto(dados,"perfil",50,true));
        if(!PERFIS.contains(papel))throw new IllegalArgumentException("Perfil inválido.");
        var criado=auth.criarColaborador(empresa,dados,papel);
        auditoria.registrar(empresa,usuario,"USUARIO_"+criado.get("id"),BigDecimal.ZERO,"USUARIO_CADASTRADO","MEDIA","Colaborador cadastrado com perfil "+papel+".");
        return criado;
    }
    @Transactional
    public Map<String,Object> alterarPerfil(int empresa,int usuario,String perfil,int id,Map<String,Object> dados){
        exigir(perfil.equals("admin"));
        String papel=normalizarPerfil(ComplianceValues.texto(dados,"perfil",50,true));
        if(!PERFIS.contains(papel))throw new IllegalArgumentException("Perfil inválido.");
        db.queryForList("SELECT pg_advisory_xact_lock(74130,?)",empresa);
        var alvo=db.queryForList("SELECT perfil FROM usuarios WHERE id=? AND empresa_id=? FOR UPDATE",id,empresa);
        if(alvo.isEmpty())throw new IllegalArgumentException("Usuário não encontrado para esta empresa.");
        if(normalizarPerfil(String.valueOf(alvo.get(0).get("perfil"))).equals("admin")&&!papel.equals("admin")&&db.queryForObject("SELECT COUNT(*) FROM usuarios WHERE empresa_id=? AND LOWER(perfil) IN ('admin','administrador')",Integer.class,empresa)<=1)throw new IllegalArgumentException("Mantenha pelo menos um administrador na empresa.");
        db.update("UPDATE usuarios SET perfil=? WHERE id=? AND empresa_id=?",papel,id,empresa);
        auditoria.registrar(empresa,usuario,"USUARIO_"+id,BigDecimal.ZERO,"PERFIL_ALTERADO","MEDIA","Perfil alterado de "+alvo.get(0).get("perfil")+" para "+papel+".");
        return Map.of("id",id,"perfil",papel);
    }
}
