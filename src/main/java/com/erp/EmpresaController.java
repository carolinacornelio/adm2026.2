package com.erp;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/empresa")
public class EmpresaController {
    private final EmpresaService service;
    private final Autorizacao aut;
    public EmpresaController(EmpresaService service, Autorizacao aut) { this.service = service; this.aut = aut; }
    @GetMapping Map<String, Object> buscar(@RequestHeader("Authorization") String a) { return service.buscar(aut.contexto(a).empresaId()); }
    @PutMapping Map<String, Object> atualizar(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizar(aut.contexto(a).empresaId(), d); }
}
