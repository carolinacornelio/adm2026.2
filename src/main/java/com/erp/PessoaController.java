package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Unifica clientes e fornecedores na API, embora eles permaneçam em tabelas
 * separadas no SQL legado. Todas as consultas filtram pela empresa do token.
 *
 * Compliance (Lei Anticorrupção): fornecedores guardam dados bancários e contrato social;
 * perfis de compras não cadastram fornecedores (SoD) e alterações bancárias de
 * não administradores vão para dupla validação (princípio dos 4 olhos).
 */
@RestController @RequestMapping("/api/pessoas")
public class PessoaController {
    private static final String CAMPOS_CLIENTE = """
            id, nome, documento, telefone, email, endereco,
            NULL AS banco, NULL AS agencia, NULL AS conta, NULL AS "chavePix",
            NULL AS "contratoSocialUrl", 'APROVADO' AS "statusConformidade",
            FALSE AS "dadosBancariosBloqueados", 'CLIENTE' AS tipo""";
    private static final String CAMPOS_FORNECEDOR = """
            id, nome, documento, telefone, email, NULL AS endereco,
            banco, agencia, conta, chave_pix AS "chavePix",
            contrato_social_url AS "contratoSocialUrl",
            COALESCE(status_conformidade, 'APROVADO') AS "statusConformidade",
            COALESCE(dados_bancarios_bloqueados, FALSE) AS "dadosBancariosBloqueados",
            'FORNECEDOR' AS tipo""";

    private final JdbcTemplate db; private final CurrentUser user; private final PosRealtimeService realtime;
    private final ComplianceService compliance;
    public PessoaController(JdbcTemplate db,CurrentUser user,PosRealtimeService realtime,ComplianceService compliance){
        this.db=db;this.user=user;this.realtime=realtime;this.compliance=compliance;
    }

    @GetMapping List<Map<String,Object>> listar(@RequestHeader("Authorization")String a){
        int e=user.empresa(a);
        List<Map<String,Object>> r=new ArrayList<>();
        r.addAll(db.queryForList("SELECT "+CAMPOS_CLIENTE+" FROM clientes WHERE empresa_id=? ORDER BY id",e));
        r.addAll(db.queryForList("SELECT "+CAMPOS_FORNECEDOR+" FROM fornecedores WHERE empresa_id=? ORDER BY id",e));
        return r;
    }
    @GetMapping("/{id}") Map<String,Object> buscar(@PathVariable int id,@RequestParam(required=false) String tipo,@RequestHeader("Authorization")String a){
        int empresa=user.empresa(a);
        if(tipo!=null) return buscarTipo(id,empresa,tipoIndividual(tipo));
        var clientes=db.queryForList("SELECT "+CAMPOS_CLIENTE+" FROM clientes WHERE id=? AND empresa_id=?",id,empresa);
        return clientes.isEmpty()?buscarTipo(id,empresa,"FORNECEDOR"):clientes.get(0);
    }
    private Map<String,Object> buscarTipo(int id,int empresa,String tipo) {
        boolean fornecedor=tipo.equals("FORNECEDOR");
        var linhas=db.queryForList("SELECT "+(fornecedor?CAMPOS_FORNECEDOR:CAMPOS_CLIENTE)+" FROM "+(fornecedor?"fornecedores":"clientes")+" WHERE id=? AND empresa_id=?",id,empresa);
        if(linhas.isEmpty())throw new IllegalArgumentException("Pessoa não encontrada.");
        return new LinkedHashMap<>(linhas.get(0));
    }

    /** Cria os dois registros quando a pessoa também for cliente e fornecedor. */
    @PostMapping @Transactional Map<String,Object> criar(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){
        int e=user.empresa(a); String tipo=tipo(d.get("tipo")); validar(d);
        // Regra ALT-03: Segregação de Funções (SoD) — perfis de compras não cadastram fornecedores.
        if (!tipo.equals("CLIENTE")) compliance.validarPermissaoCadastroFornecedor(e, user.usuario(a), user.perfil(a));
        Number clienteId = null, fornecedorId = null;
        if (!tipo.equals("FORNECEDOR")) clienteId = inserir("clientes", e, d);
        if (!tipo.equals("CLIENTE")) fornecedorId = inserir("fornecedores", e, d);
        Map<String,Object> resposta = new LinkedHashMap<>();
        resposta.put("id", clienteId != null ? clienteId : fornecedorId);
        resposta.put("clienteId", clienteId); resposta.put("fornecedorId", fornecedorId);
        resposta.put("nome", AuthService.texto(d,"nome")); resposta.put("tipo", tipo);
        resposta.put("documento", opcional(d,"documento"));
        realtime.publicarDepoisDoCommit(e,"catalogo-atualizado");
        return resposta;
    }

    @PutMapping("/{id}") @Transactional Map<String,Object> atualizar(@PathVariable int id,@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){
        int empresa=user.empresa(a);String tipo=tipoIndividual(d.get("tipo"));validar(d);
        if(tipo.equals("FORNECEDOR")) {
            Map<String,Object> retido=reterAlteracaoBancaria(id,empresa,d,a);
            if(retido!=null) return retido;
        }
        String tabela=tipo.equals("FORNECEDOR")?"fornecedores":"clientes";
        String sql="UPDATE "+tabela+" SET nome=?,documento=?,telefone=?,email=?"
                +(tabela.equals("clientes")?",endereco=?":",banco=?,agencia=?,conta=?,chave_pix=?,contrato_social_url=?")
                +" WHERE id=? AND empresa_id=?";
        List<Object> valores=new ArrayList<>();Collections.addAll(valores,AuthService.texto(d,"nome"),documento(d),opcional(d,"telefone"),opcional(d,"email"));
        if(tabela.equals("clientes")) valores.add(opcional(d,"endereco"));
        else Collections.addAll(valores,opcional(d,"banco"),opcional(d,"agencia"),opcional(d,"conta"),opcional(d,"chavePix"),opcional(d,"contratoSocialUrl"));
        valores.add(id);valores.add(empresa);
        if(db.update(sql,valores.toArray())==0)throw new IllegalArgumentException("Pessoa não encontrada.");
        realtime.publicarDepoisDoCommit(empresa,"catalogo-atualizado");return buscarTipo(id,empresa,tipo);
    }

    /**
     * Princípio dos 4 olhos: se um não administrador altera dados bancários de um fornecedor,
     * a alteração fica pendente, o fornecedor é bloqueado para movimentações e só os dados
     * cadastrais são atualizados. Devolve null quando a alteração pode seguir normalmente.
     */
    private Map<String,Object> reterAlteracaoBancaria(int id,int empresa,Map<String,Object> d,String a) {
        var atual=db.queryForList("SELECT banco, agencia, conta, chave_pix FROM fornecedores WHERE id=? AND empresa_id=?",id,empresa);
        if(atual.isEmpty()) return null;
        Map<String,Object> dados=atual.get(0);
        String banco=opcional(d,"banco"), agencia=opcional(d,"agencia"), conta=opcional(d,"conta"), pix=opcional(d,"chavePix");
        boolean alterouBancario=!Objects.equals(opcional(dados,"banco"),banco) || !Objects.equals(opcional(dados,"agencia"),agencia)
                || !Objects.equals(opcional(dados,"conta"),conta) || !Objects.equals(opcional(dados,"chave_pix"),pix);
        if(!alterouBancario) return null;
        String arquivoUrl=opcional(d,"arquivoComprobatorioUrl");
        if(arquivoUrl.isBlank()) arquivoUrl=opcional(d,"contratoSocialUrl");
        if(arquivoUrl.isBlank()) throw new ComplianceException("COMPLIANCE_COMPROVANTE_OBRIGATORIO",
                "Para alterar dados bancários de fornecedores, é obrigatório anexar comprovante de domicílio bancário ou contrato físico.");
        if(user.perfil(a).equals("admin")) return null;
        int usuarioId=user.usuario(a);
        db.update("""
                INSERT INTO fornecedores_alteracoes_pendentes
                    (empresa_id, fornecedor_id, solicitante_id, banco, agencia, conta, chave_pix, arquivo_comprobatorio_url, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDENTE')
                """, empresa, id, usuarioId, banco, agencia, conta, pix, arquivoUrl);
        db.update("UPDATE fornecedores SET dados_bancarios_bloqueados = TRUE WHERE id = ? AND empresa_id = ?", id, empresa);
        compliance.registrarAlerta(empresa, usuarioId, "FORNECEDOR_ID_" + id, 0.0,
                "ALTERACAO_DADOS_BANCARIOS_PENDENTE", "MEDIA",
                "Solicitação de alteração de dados bancários enviada para o Princípio de Quatro Olhos.");
        db.update("UPDATE fornecedores SET nome=?, documento=?, telefone=?, email=? WHERE id=? AND empresa_id=?",
                AuthService.texto(d,"nome"), documento(d), opcional(d,"telefone"), opcional(d,"email"), id, empresa);
        realtime.publicarDepoisDoCommit(empresa,"catalogo-atualizado");
        Map<String,Object> resposta=buscarTipo(id,empresa,"FORNECEDOR");
        resposta.put("avisoCompliance", "Dados bancários retidos para aprovação de segundo usuário (Princípio de 4 Olhos).");
        return resposta;
    }

    /** O tipo acompanha a exclusão para não apagar um fornecedor de mesmo id que um cliente. */
    @DeleteMapping("/{id}") @Transactional void remover(@PathVariable int id,@RequestParam(defaultValue="CLIENTE") String tipo,@RequestHeader("Authorization")String a){
        int e=user.empresa(a); String tabela = tipoIndividual(tipo).equals("FORNECEDOR") ? "fornecedores" : "clientes";
        if(db.update("DELETE FROM "+tabela+" WHERE id=? AND empresa_id=?",id,e)==0)throw new IllegalArgumentException("Pessoa não encontrada.");
        realtime.publicarDepoisDoCommit(e,"catalogo-atualizado");
    }
    private Number inserir(String tabela, int empresaId, Map<String,Object> dados) {
        if (tabela.equals("clientes"))
            return db.queryForObject("INSERT INTO clientes(empresa_id,nome,documento,telefone,email,endereco) VALUES(?,?,?,?,?,?) RETURNING id",
                    Number.class, empresaId, AuthService.texto(dados,"nome"), documento(dados), opcional(dados,"telefone"), opcional(dados,"email"), opcional(dados,"endereco"));
        return db.queryForObject("""
                INSERT INTO fornecedores(empresa_id, nome, documento, telefone, email, banco, agencia, conta, chave_pix, contrato_social_url, status_conformidade)
                VALUES(?,?,?,?,?,?,?,?,?,?,'APROVADO') RETURNING id
                """, Number.class, empresaId, AuthService.texto(dados,"nome"), documento(dados), opcional(dados,"telefone"), opcional(dados,"email"),
                opcional(dados,"banco"), opcional(dados,"agencia"), opcional(dados,"conta"), opcional(dados,"chavePix"), opcional(dados,"contratoSocialUrl"));
    }
    private String tipo(Object valor) { String tipo=String.valueOf(valor==null?"CLIENTE":valor).trim().toUpperCase(Locale.ROOT); if(!Set.of("CLIENTE","FORNECEDOR","CLIENTE_FORNECEDOR").contains(tipo))throw new IllegalArgumentException("Tipo de pessoa inválido."); return tipo; }
    private String tipoIndividual(Object valor) { String tipo=tipo(valor);if(tipo.equals("CLIENTE_FORNECEDOR"))throw new IllegalArgumentException("Informe CLIENTE ou FORNECEDOR para consultar, alterar ou excluir um registro.");return tipo; }
    private String documento(Map<String,Object> dados) { String valor=opcional(dados,"documento");return valor.isBlank()?null:valor; }
    private String opcional(Map<String,Object> dados,String chave) { Object valor=dados.get(chave); return valor==null?"":valor.toString().trim(); }
    private void validar(Map<String,Object> dados) {
        if(AuthService.texto(dados,"nome").length()>150||opcional(dados,"documento").length()>20||opcional(dados,"telefone").length()>20
                ||opcional(dados,"email").length()>150||opcional(dados,"endereco").length()>255
                ||opcional(dados,"banco").length()>50||opcional(dados,"agencia").length()>20||opcional(dados,"conta").length()>30
                ||opcional(dados,"chavePix").length()>100||opcional(dados,"contratoSocialUrl").length()>500)
            throw new IllegalArgumentException("Um dos campos excede o tamanho permitido.");
    }
}
