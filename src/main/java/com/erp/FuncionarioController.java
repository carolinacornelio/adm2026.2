package com.erp;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Gestão de funcionários (subcontas). Empresa e autor vêm sempre do token, nunca do corpo da requisição. */
@RestController
@RequestMapping("/api/funcionarios")
public class FuncionarioController {
    private final FuncionarioService service;
    private final Autorizacao aut;
    public FuncionarioController(FuncionarioService service, Autorizacao aut) { this.service = service; this.aut = aut; }

    @GetMapping List<Map<String, Object>> listar(@RequestParam(required = false) String status, @RequestParam(required = false) String busca, @RequestHeader("Authorization") String a) {
        return service.listar(aut.contexto(a).empresaId(), status, busca);
    }
    @GetMapping("/{id}") Map<String, Object> buscar(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.buscar(aut.contexto(a).empresaId(), id); }
    @PostMapping Map<String, Object> criar(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.criar(aut.contexto(a), d); }
    @PutMapping("/{id}") Map<String, Object> atualizar(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizar(aut.contexto(a), id, d); }
    @PutMapping("/{id}/status") Map<String, Object> status(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) {
        if (!(d.get("ativo") instanceof Boolean ativo)) throw new IllegalArgumentException("Informe ativo como verdadeiro ou falso.");
        return service.alterarStatus(aut.contexto(a), id, ativo);
    }
    @PostMapping("/{id}/senha") Map<String, Object> redefinirSenha(@PathVariable int id, @RequestBody(required = false) Map<String, Object> d, @RequestHeader("Authorization") String a) {
        return service.redefinirSenha(aut.contexto(a), id, d == null ? Map.of() : d);
    }
}
