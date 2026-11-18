package com.erp;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/squads")
public class SquadController {
    private final SquadService service;
    private final Autorizacao aut;
    public SquadController(SquadService service, Autorizacao aut) { this.service = service; this.aut = aut; }

    @GetMapping List<Map<String, Object>> listar(@RequestParam(required = false) String status, @RequestHeader("Authorization") String a) { return service.listar(aut.contexto(a).empresaId(), status); }
    @GetMapping("/{id}") Map<String, Object> buscar(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.buscar(aut.contexto(a).empresaId(), id); }
    @GetMapping("/{id}/candidatos") List<Map<String, Object>> candidatos(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.candidatos(aut.contexto(a).empresaId(), id); }
    @PostMapping Map<String, Object> criar(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.criar(aut.contexto(a), d); }
    @PutMapping("/{id}") Map<String, Object> atualizar(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizar(aut.contexto(a).empresaId(), id, d); }
    @PostMapping("/{id}/integrantes") Map<String, Object> adicionar(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) {
        return service.adicionarIntegrantes(aut.contexto(a).empresaId(), id, Dados.inteiros(d, "usuarios"));
    }
    @DeleteMapping("/{id}/integrantes/{usuarioId}") Map<String, Object> remover(@PathVariable int id, @PathVariable int usuarioId, @RequestHeader("Authorization") String a) { return service.removerIntegrante(aut.contexto(a).empresaId(), id, usuarioId); }
    @PutMapping("/{id}/lider") Map<String, Object> lider(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.definirLider(aut.contexto(a).empresaId(), id, Dados.inteiro(d, "usuarioId", false)); }
}
