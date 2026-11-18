package com.erp;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Perfis de acesso e catálogo de permissões. A permissão exigida de cada rota está em PermissaoInterceptor. */
@RestController
public class PerfilController {
    private final PerfilService service;
    private final Autorizacao aut;
    public PerfilController(PerfilService service, Autorizacao aut) { this.service = service; this.aut = aut; }

    @GetMapping("/api/permissoes") List<Map<String, Object>> catalogo(@RequestHeader("Authorization") String a) { aut.contexto(a); return service.catalogo(); }
    @GetMapping("/api/perfis") List<Map<String, Object>> listar(@RequestHeader("Authorization") String a) { return service.listar(aut.contexto(a).empresaId()); }
    @GetMapping("/api/perfis/{id}") Map<String, Object> buscar(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.buscar(aut.contexto(a).empresaId(), id); }
    @PostMapping("/api/perfis") Map<String, Object> criar(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.criar(aut.contexto(a), d); }
    @PutMapping("/api/perfis/{id}") Map<String, Object> atualizar(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizar(aut.contexto(a), id, d); }
    @DeleteMapping("/api/perfis/{id}") void excluir(@PathVariable int id, @RequestHeader("Authorization") String a) { service.excluir(aut.contexto(a), id); }
}
