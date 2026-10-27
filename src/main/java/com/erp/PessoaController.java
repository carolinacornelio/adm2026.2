package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Unifica clientes e fornecedores na API, embora eles permaneçam em tabelas
 * separadas no SQL legado. Todas as consultas filtram pela empresa do token.
 */
@RestController @RequestMapping("/api/pessoas")
public class PessoaController {
    private final JdbcTemplate db; private final CurrentUser user; private final PosRealtimeService realtime;private final ComplianceService compliance;
    public PessoaController(JdbcTemplate db,CurrentUser user,PosRealtimeService realtime,ComplianceService compliance){this.db=db;this.user=user;this.realtime=realtime;this.compliance=compliance;}
    @GetMapping List<Map<String,Object>> listar(@RequestHeader("Authorization")String a){int e=user.empresa(a);List<Map<String,Object>> r=new ArrayList<>();r.addAll(db.queryForList("SELECT id,nome,documento,telefone,email,endereco,'CLIENTE' AS tipo FROM clientes WHERE empresa_id=? ORDER BY id",e));r.addAll(db.queryForList("SELECT id,nome,documento,telefone,email,NULL AS endereco,'FORNECEDOR' AS tipo FROM fornecedores WHERE empresa_id=? ORDER BY id",e));return r;}
    @GetMapping("/{id}") Map<String,Object> buscar(@PathVariable int id,@RequestParam(required=false) String tipo,@RequestHeader("Authorization")String a){
        int empresa=user.empresa(a);
        if(tipo!=null) return buscarTipo(id,empresa,tipoIndividual(tipo));
        var clientes=db.queryForList("SELECT id,nome,documento,telefone,email,endereco,'CLIENTE' AS tipo FROM clientes WHERE id=? AND empresa_id=?",id,empresa);
        return clientes.isEmpty()?buscarTipo(id,empresa,"FORNECEDOR"):clientes.get(0);
    }
    private Map<String,Object> buscarTipo(int id,int empresa,String tipo) {
        String tabela=tipo.equals("FORNECEDOR")?"fornecedores":"clientes";
        var linhas=db.queryForList("SELECT id,nome,documento,telefone,email,"+(tabela.equals("clientes")?"endereco":"NULL AS endereco")+",? AS tipo FROM "+tabela+" WHERE id=? AND empresa_id=?",tipo,id,empresa);
        if(linhas.isEmpty())throw new IllegalArgumentException("Pessoa não encontrada.");
        return linhas.get(0);
    }
    /** Cria os dois registros quando a pessoa também for cliente e fornecedor. */
    @PostMapping @Transactional Map<String,Object> criar(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){
        int e=user.empresa(a); String tipo=tipo(d.get("tipo")); validar(d);
        if(!tipo.equals("CLIENTE")){compliance.validarFornecedorCadastro(e,user.usuario(a),user.perfil(a));validarCamposBancarios(d);}
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
        if(tipo.equals("FORNECEDOR")){compliance.validarFornecedorCadastro(empresa,user.usuario(a),user.perfil(a));validarCamposBancarios(d);}
        String tabela=tipo.equals("FORNECEDOR")?"fornecedores":"clientes";
        String sql="UPDATE "+tabela+" SET nome=?,documento=?,telefone=?,email=?"+(tabela.equals("clientes")?",endereco=?":"")+" WHERE id=? AND empresa_id=?";
        List<Object> valores=new ArrayList<>();Collections.addAll(valores,AuthService.texto(d,"nome"),documento(d),opcional(d,"telefone"),opcional(d,"email"));
        if(tabela.equals("clientes"))valores.add(opcional(d,"endereco"));
        valores.add(id);valores.add(empresa);
        if(db.update(sql,valores.toArray())==0)throw new IllegalArgumentException("Pessoa não encontrada.");
        realtime.publicarDepoisDoCommit(empresa,"catalogo-atualizado");return buscarTipo(id,empresa,tipo);
    }
    /** O tipo acompanha a exclusão para não apagar um fornecedor de mesmo id que um cliente. */
    @DeleteMapping("/{id}") @Transactional void remover(@PathVariable int id,@RequestParam(defaultValue="CLIENTE") String tipo,@RequestHeader("Authorization")String a){
        int e=user.empresa(a); String tabela = tipoIndividual(tipo).equals("FORNECEDOR") ? "fornecedores" : "clientes";
        if(tabela.equals("fornecedores"))compliance.validarFornecedorCadastro(e,user.usuario(a),user.perfil(a));
        if(db.update("DELETE FROM "+tabela+" WHERE id=? AND empresa_id=?",id,e)==0)throw new IllegalArgumentException("Pessoa não encontrada.");
        realtime.publicarDepoisDoCommit(e,"catalogo-atualizado");
    }
    private Number inserir(String tabela, int empresaId, Map<String,Object> dados) {
        boolean cliente = tabela.equals("clientes");
        String sql="INSERT INTO "+tabela+"(empresa_id,nome,documento,telefone,email"+(cliente?",endereco":"")+") VALUES(?,?,?,?,?"+(cliente?",?":"")+") RETURNING id";
        List<Object> valores=new ArrayList<>();Collections.addAll(valores,empresaId,AuthService.texto(dados,"nome"),documento(dados),opcional(dados,"telefone"),opcional(dados,"email"));
        if(cliente) valores.add(opcional(dados,"endereco"));
        return db.queryForObject(sql,Number.class,valores.toArray());
    }
    private String tipo(Object valor) { String tipo=String.valueOf(valor==null?"CLIENTE":valor).trim().toUpperCase(Locale.ROOT); if(!Set.of("CLIENTE","FORNECEDOR","CLIENTE_FORNECEDOR").contains(tipo))throw new IllegalArgumentException("Tipo de pessoa inválido."); return tipo; }
    private String tipoIndividual(Object valor) { String tipo=tipo(valor);if(tipo.equals("CLIENTE_FORNECEDOR"))throw new IllegalArgumentException("Informe CLIENTE ou FORNECEDOR para consultar, alterar ou excluir um registro.");return tipo; }
    private void validarCamposBancarios(Map<String,Object> dados) {
        if(java.util.stream.Stream.of("banco","agencia","conta","chavePix").anyMatch(dados::containsKey))throw new IllegalArgumentException("Solicite alterações bancárias pelo módulo Compliance para obter a validação de outra pessoa.");
    }
    private String documento(Map<String,Object> dados) { String valor=opcional(dados,"documento");return valor.isBlank()?null:valor; }
    private String opcional(Map<String,Object> dados,String chave) { Object valor=dados.get(chave); return valor==null?"":valor.toString().trim(); }
    private void validar(Map<String,Object> dados) { if(AuthService.texto(dados,"nome").length()>150||opcional(dados,"documento").length()>20||opcional(dados,"telefone").length()>20||opcional(dados,"email").length()>150||opcional(dados,"endereco").length()>255)throw new IllegalArgumentException("Um dos campos excede o tamanho permitido."); }
}
