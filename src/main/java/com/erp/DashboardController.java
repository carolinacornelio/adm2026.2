package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Indicadores calculados no banco, evitando carregar todos os registros no browser. */
@RestController @RequestMapping("/api/dashboard")
public class DashboardController {
    private final JdbcTemplate db; private final CurrentUser user;
    public DashboardController(JdbcTemplate db,CurrentUser user){this.db=db;this.user=user;}
    @GetMapping("/indicadores") Map<String,Object> indicadores(@RequestHeader("Authorization")String a){int e=user.empresa(a);return Map.of(
        "totalPessoas", count("SELECT COUNT(*) FROM clientes WHERE empresa_id=?",e)+count("SELECT COUNT(*) FROM fornecedores WHERE empresa_id=?",e),
        "totalProdutos",count("SELECT COUNT(*) FROM produtos WHERE empresa_id=? AND ativo=true",e),
        "totalCompras",count("SELECT COUNT(*) FROM compras WHERE empresa_id=?",e),
        "totalVendas",count("SELECT COUNT(*) FROM vendas WHERE empresa_id=?",e));}
    private int count(String sql,int e){return db.queryForObject(sql,Integer.class,e);}
}
