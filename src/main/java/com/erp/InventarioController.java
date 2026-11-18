package com.erp;

import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/inventario")
public class InventarioController {
    private final JdbcTemplate db;
    private final CurrentUser user;
    private final InventarioService inventario;
    public InventarioController(JdbcTemplate db,CurrentUser user,InventarioService inventario) {this.db=db;this.user=user;this.inventario=inventario;}

    @GetMapping("/lotes")
    public List<Map<String,Object>> lotes(@RequestHeader("Authorization") String a,@RequestParam(defaultValue="30") int dias) {
        if(dias<0 || dias>365) throw new IllegalArgumentException("Informe um prazo de alerta de 0 a 365 dias.");
        var lotes = db.queryForList("""
            SELECT l.id,l.produto_id AS "produtoId",p.nome,p.codigo AS "codigoProduto",l.codigo AS lote,
                   l.validade,l.quantidade,l.legado,p.controla_validade AS "controlaValidade"
              FROM estoque_lotes l JOIN produtos p ON p.id=l.produto_id AND p.empresa_id=l.empresa_id
             WHERE l.empresa_id=? AND p.ativo=true AND l.quantidade>0
             ORDER BY l.validade ASC NULLS LAST,p.nome,l.id
            """,user.empresa(a));
        var hoje = InventarioService.hoje();
        lotes.forEach(lote -> ValidadeStatus.aplicar(lote, hoje, dias));
        return lotes;
    }

    @PutMapping("/lotes/{id}/validade")
    public void definirValidade(@PathVariable int id,@RequestBody Map<String,Object> dados,@RequestHeader("Authorization") String a) {
        LocalDate validade;
        try { validade=LocalDate.parse(String.valueOf(dados.get("validade"))); }
        catch(RuntimeException ex) { throw new IllegalArgumentException("Informe uma data de validade válida."); }
        inventario.definirValidade(user.empresa(a),id,String.valueOf(dados.getOrDefault("lote","")),validade);
    }

    @PutMapping("/produtos/{id}/controle-validade")
    @org.springframework.transaction.annotation.Transactional
    public void controlar(@PathVariable int id,@RequestBody Map<String,Object> dados,@RequestHeader("Authorization") String a) {
        int empresa=user.empresa(a);
        if(!(dados.get("controlaValidade") instanceof Boolean)) throw new IllegalArgumentException("Informe o controle de validade.");
        if(db.update("UPDATE produtos SET controla_validade=? WHERE id=? AND empresa_id=? AND ativo=true",dados.get("controlaValidade"),id,empresa)!=1)
            throw new IllegalArgumentException("Produto não encontrado.");
        inventario.notificar(empresa);
    }
}
