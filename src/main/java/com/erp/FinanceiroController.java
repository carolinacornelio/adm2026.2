package com.erp;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * Financeiro. {tipo} = "pagar" ou "receber". Rotas literais (resumo, categorias) têm precedência sobre {tipo}.
 * Liquidar e cancelar são sub-recursos para que o interceptor exija finance.update (ver PermissaoInterceptor).
 */
@RestController
@RequestMapping("/api/financeiro")
public class FinanceiroController {
    private final FinanceiroService service;
    private final Autorizacao aut;
    public FinanceiroController(FinanceiroService service, Autorizacao aut) { this.service = service; this.aut = aut; }

    @GetMapping("/resumo") Map<String, Object> resumo(@RequestHeader("Authorization") String a) { return service.resumo(aut.contexto(a).empresaId()); }
    @GetMapping("/categorias") List<Map<String, Object>> categorias(@RequestParam(required = false) String tipo, @RequestHeader("Authorization") String a) { return service.categorias(aut.contexto(a).empresaId(), tipo); }
    @PostMapping("/categorias") Map<String, Object> criarCategoria(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.criarCategoria(aut.contexto(a).empresaId(), d); }
    @PutMapping("/categorias/{id}") Map<String, Object> atualizarCategoria(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizarCategoria(aut.contexto(a).empresaId(), id, d); }

    @GetMapping("/{tipo}") List<Map<String, Object>> listar(@PathVariable String tipo, @RequestParam(required = false) String situacao, @RequestParam(required = false) String de,
            @RequestParam(required = false) String ate, @RequestParam(required = false) Integer categoriaId, @RequestParam(required = false) String busca, @RequestHeader("Authorization") String a) {
        return service.listar(aut.contexto(a).empresaId(), FinanceiroService.Tipo.de(tipo), situacao,
                de == null || de.isBlank() ? null : Dados.data(Map.of("de", de), "de", true), ate == null || ate.isBlank() ? null : Dados.data(Map.of("ate", ate), "ate", true), categoriaId, busca);
    }
    @GetMapping("/{tipo}/{id}") Map<String, Object> buscar(@PathVariable String tipo, @PathVariable int id, @RequestHeader("Authorization") String a) { return service.buscar(aut.contexto(a).empresaId(), FinanceiroService.Tipo.de(tipo), id); }
    @PostMapping("/{tipo}") Map<String, Object> criar(@PathVariable String tipo, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.criar(aut.contexto(a), FinanceiroService.Tipo.de(tipo), d); }
    @PutMapping("/{tipo}/{id}") Map<String, Object> atualizar(@PathVariable String tipo, @PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizar(aut.contexto(a), FinanceiroService.Tipo.de(tipo), id, d); }
    @PostMapping("/{tipo}/{id}/pagamento") Map<String, Object> liquidar(@PathVariable String tipo, @PathVariable int id, @RequestBody(required = false) Map<String, Object> d, @RequestHeader("Authorization") String a) {
        return service.liquidar(aut.contexto(a), FinanceiroService.Tipo.de(tipo), id, d == null ? Map.of() : d);
    }
    @PostMapping("/{tipo}/{id}/cancelamento") Map<String, Object> cancelar(@PathVariable String tipo, @PathVariable int id, @RequestBody(required = false) Map<String, Object> d, @RequestHeader("Authorization") String a) {
        return service.cancelar(aut.contexto(a), FinanceiroService.Tipo.de(tipo), id, d == null ? Map.of() : d);
    }
}
