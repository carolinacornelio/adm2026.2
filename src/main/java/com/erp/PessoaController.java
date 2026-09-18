package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
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
    @PostMapping Map<String,Object> criar(@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){int e=user.empresa(a);String tipo=String.valueOf(d.getOrDefault("tipo","CLIENTE"));String tabela=tipo.contains("FORNECEDOR")&&!tipo.contains("CLIENTE")?"fornecedores":"clientes";String sql="INSERT INTO "+tabela+"(empresa_id,nome,documento,telefone,email"+(tabela.equals("clientes")?",endereco":"")+") VALUES(?,?,?,?,?"+(tabela.equals("clientes")?",?":"")+") RETURNING id";List<Object> v=new ArrayList<>(List.of(e,AuthService.texto(d,"nome"),d.getOrDefault("documento",""),d.getOrDefault("telefone",""),d.getOrDefault("email","")));if(tabela.equals("clientes"))v.add(d.getOrDefault("endereco",""));Number id=db.queryForObject(sql,Number.class,v.toArray());return Map.of("id",id,"nome",d.get("nome"),"tipo",tipo,"documento",d.getOrDefault("documento",""));}
    @PutMapping("/{id}") Map<String,Object> atualizar(@PathVariable int id,@RequestBody Map<String,Object>d,@RequestHeader("Authorization")String a){int e=user.empresa(a);String tipo=String.valueOf(d.getOrDefault("tipo","CLIENTE"));String tabela=tipo.contains("FORNECEDOR")&&!tipo.contains("CLIENTE")?"fornecedores":"clientes";String sql="UPDATE "+tabela+" SET nome=?,documento=?,telefone=?,email=?"+(tabela.equals("clientes")?",endereco=?":"")+" WHERE id=? AND empresa_id=?";List<Object>v=new ArrayList<>(List.of(AuthService.texto(d,"nome"),d.getOrDefault("documento",""),d.getOrDefault("telefone",""),d.getOrDefault("email","")));if(tabela.equals("clientes"))v.add(d.getOrDefault("endereco",""));v.add(id);v.add(e);if(db.update(sql,v.toArray())==0)throw new IllegalArgumentException("Pessoa não encontrada.");return buscar(id,a);}
    @DeleteMapping("/{id}") void remover(@PathVariable int id,@RequestHeader("Authorization")String a){int e=user.empresa(a);if(db.update("DELETE FROM clientes WHERE id=? AND empresa_id=?",id,e)==0&&db.update("DELETE FROM fornecedores WHERE id=? AND empresa_id=?",id,e)==0)throw new IllegalArgumentException("Pessoa não encontrada.");}
}
