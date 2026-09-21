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
    private final JdbcTemplate db; private final CurrentUser user;
    public PessoaController(JdbcTemplate db,CurrentUser user){this.db=db;this.user=user;}
    @GetMapping List<Map<String,Object>> listar(@RequestHeader("Authorization")String a){int e=user.empresa(a);List<Map<String,Object>> r=new ArrayList<>();r.addAll(db.queryForList("SELECT id,nome,documento,telefone,email,endereco,'CLIENTE' AS tipo FROM clientes WHERE empresa_id=? ORDER BY id",e));r.addAll(db.queryForList("SELECT id,nome,documento,telefone,email,NULL AS endereco,'FORNECEDOR' AS tipo FROM fornecedores WHERE empresa_id=? ORDER BY id",e));return r;}
    @GetMapping("/{id}") Map<String,Object> buscar(@PathVariable int id,@RequestHeader("Authorization")String a){int e=user.empresa(a);List<Map<String,Object>>r=db.queryForList("SELECT id,nome,documento,telefone,email,endereco,'CLIENTE' AS tipo FROM clientes WHERE id=? AND empresa_id=?",id,e);if(r.isEmpty())r=db.queryForList("SELECT id,nome,documento,telefone,email,NULL AS endereco,'FORNECEDOR' AS tipo FROM fornecedores WHERE id=? AND empresa_id=?",id,e);if(r.isEmpty())throw new IllegalArgumentException("Pessoa não encontrada.");return r.get(0);}
    /** Cria os dois registros quando a pessoa também for cliente e fornecedor. */
    @PostMapping @Transactional Map<String,Object> criar(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){
        int e=user.empresa(a); String tipo=tipo(d.get("tipo")); validar(d);
        Number clienteId = null, fornecedorId = null;
        if (!tipo.equals("FORNECEDOR")) clienteId = inserir("clientes", e, d);
        if (!tipo.equals("CLIENTE")) fornecedorId = inserir("fornecedores", e, d);
        Map<String,Object> resposta = new LinkedHashMap<>();
        resposta.put("id", clienteId != null ? clienteId : fornecedorId);
        resposta.put("clienteId", clienteId); resposta.put("fornecedorId", fornecedorId);
        resposta.put("nome", AuthService.texto(d,"nome")); resposta.put("tipo", tipo);
        resposta.put("documento", opcional(d,"documento"));
        return resposta;
    }
    @PutMapping("/{id}") Map<String,Object> atualizar(@PathVariable int id,@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){int e=user.empresa(a);String tipo=String.valueOf(d.getOrDefault("tipo","CLIENTE"));String tabela=tipo.contains("FORNECEDOR")&&!tipo.contains("CLIENTE")?"fornecedores":"clientes";String sql="UPDATE "+tabela+" SET nome=?,documento=?,telefone=?,email=?"+(tabela.equals("clientes")?",endereco=?":"")+" WHERE id=? AND empresa_id=?";List<Object>v=new ArrayList<>(List.of(AuthService.texto(d,"nome"),d.getOrDefault("documento",""),d.getOrDefault("telefone",""),d.getOrDefault("email","")));if(tabela.equals("clientes"))v.add(d.getOrDefault("endereco",""));v.add(id);v.add(e);if(db.update(sql,v.toArray())==0)throw new IllegalArgumentException("Pessoa não encontrada.");return buscar(id,a);}
    /** O tipo acompanha a exclusão para não apagar um fornecedor de mesmo id que um cliente. */
    @DeleteMapping("/{id}") void remover(@PathVariable int id,@RequestParam(defaultValue="CLIENTE") String tipo,@RequestHeader("Authorization")String a){
        int e=user.empresa(a); String tabela = tipo(tipo).equals("FORNECEDOR") ? "fornecedores" : "clientes";
        if(db.update("DELETE FROM "+tabela+" WHERE id=? AND empresa_id=?",id,e)==0)throw new IllegalArgumentException("Pessoa não encontrada.");
    }
    private Number inserir(String tabela, int empresaId, Map<String,Object> dados) {
        boolean cliente = tabela.equals("clientes");
        String sql="INSERT INTO "+tabela+"(empresa_id,nome,documento,telefone,email"+(cliente?",endereco":"")+") VALUES(?,?,?,?,?"+(cliente?",?":"")+") RETURNING id";
        List<Object> valores=new ArrayList<>(List.of(empresaId,AuthService.texto(dados,"nome"),opcional(dados,"documento"),opcional(dados,"telefone"),opcional(dados,"email")));
        if(cliente) valores.add(opcional(dados,"endereco"));
        return db.queryForObject(sql,Number.class,valores.toArray());
    }
    private String tipo(Object valor) { String tipo=String.valueOf(valor==null?"CLIENTE":valor).toUpperCase(Locale.ROOT); if(!Set.of("CLIENTE","FORNECEDOR","CLIENTE_FORNECEDOR").contains(tipo))throw new IllegalArgumentException("Tipo de pessoa inválido."); return tipo; }
    private String opcional(Map<String,Object> dados,String chave) { Object valor=dados.get(chave); return valor==null?"":valor.toString().trim(); }
    private void validar(Map<String,Object> dados) { if(opcional(dados,"documento").length()>20||opcional(dados,"telefone").length()>20||opcional(dados,"email").length()>150||opcional(dados,"endereco").length()>255)throw new IllegalArgumentException("Um dos campos excede o tamanho permitido."); }
}
