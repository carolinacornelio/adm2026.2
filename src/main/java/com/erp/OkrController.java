package com.erp;

import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** OKRs: objetivos, resultados-chave, progresso e histórico. Permissões por rota em PermissaoInterceptor. */
@RestController
@RequestMapping("/api/okrs")
public class OkrController {
    private final OkrService service;
    private final Autorizacao aut;
    public OkrController(OkrService service, Autorizacao aut) { this.service = service; this.aut = aut; }

    @GetMapping("/objetivos") List<Map<String, Object>> listar(@RequestParam(required = false) String status, @RequestParam(required = false) Integer squadId,
            @RequestParam(required = false) Integer responsavelId, @RequestParam(required = false) String de, @RequestParam(required = false) String ate,
            @RequestHeader("Authorization") String a) {
        return service.listarObjetivos(aut.contexto(a).empresaId(), status, squadId, responsavelId,
                de == null || de.isBlank() ? null : Dados.data(Map.of("de", de), "de", true), ate == null || ate.isBlank() ? null : Dados.data(Map.of("ate", ate), "ate", true));
    }
    @GetMapping("/objetivos/{id}") Map<String, Object> objetivo(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.buscarObjetivo(aut.contexto(a).empresaId(), id); }
    @PostMapping("/objetivos") Map<String, Object> criar(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.criarObjetivo(aut.contexto(a), d); }
    @PutMapping("/objetivos/{id}") Map<String, Object> atualizar(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizarObjetivo(aut.contexto(a).empresaId(), id, d); }
    @DeleteMapping("/objetivos/{id}") Map<String, Object> arquivar(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.arquivarObjetivo(aut.contexto(a).empresaId(), id); }
    @PostMapping("/objetivos/{id}/resultados") Map<String, Object> criarResultado(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.criarResultado(aut.contexto(a), id, d); }
    @PostMapping("/resultados/{id}/submetas") Map<String, Object> criarSubMeta(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.criarSubMeta(aut.contexto(a), id, d); }
    @PutMapping("/submetas/{id}/conclusao") Map<String, Object> concluirSubMeta(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizarConclusaoSubMeta(aut.contexto(a), id, d); }
    @PutMapping("/submetas/{id}") Map<String, Object> atualizarSubMeta(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizarSubMeta(aut.contexto(a), id, d); }
    @DeleteMapping("/submetas/{id}") Map<String, Object> removerSubMeta(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.removerSubMeta(aut.contexto(a), id); }
    @GetMapping("/resultados/{id}") Map<String, Object> resultado(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.buscarResultado(aut.contexto(a).empresaId(), id); }
    @PutMapping("/resultados/{id}") Map<String, Object> atualizarResultado(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizarResultado(aut.contexto(a).empresaId(), id, d); }
    @DeleteMapping("/resultados/{id}") Map<String, Object> cancelar(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.cancelarResultado(aut.contexto(a).empresaId(), id); }
    @PutMapping("/resultados/{id}/progresso") Map<String, Object> progresso(@PathVariable int id, @RequestBody Map<String, Object> d, @RequestHeader("Authorization") String a) { return service.atualizarProgresso(aut.contexto(a), id, d); }
    @GetMapping("/resultados/{id}/historico") List<Map<String, Object>> historico(@PathVariable int id, @RequestHeader("Authorization") String a) { return service.historico(aut.contexto(a).empresaId(), id); }
    @GetMapping("/progresso") Map<String, Object> resumo(@RequestHeader("Authorization") String a) { return service.resumo(aut.contexto(a).empresaId()); }
}
