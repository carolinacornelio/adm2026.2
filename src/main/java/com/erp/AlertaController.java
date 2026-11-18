package com.erp;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/alertas")
public class AlertaController {
    private final CurrentUser user;
    private final AlertaService service;
    public AlertaController(CurrentUser user,AlertaService service) { this.user=user; this.service=service; }
    @PostMapping("/verificar")
    public Map<String,Object> verificar(@RequestHeader("Authorization") String auth) {
        int empresa=user.empresa(auth), usuario=user.usuario(auth);
        service.atualizar(empresa);
        return service.listar(empresa,usuario,0);
    }
    @GetMapping
    public Map<String,Object> historico(@RequestHeader("Authorization") String auth,@RequestParam(defaultValue="0") int pagina) {
        return service.listar(user.empresa(auth),user.usuario(auth),pagina);
    }
    @PostMapping("/lidos")
    public Map<String,Boolean> ler(@RequestHeader("Authorization") String auth,@RequestBody List<Long> ids) {
        service.ler(user.empresa(auth),user.usuario(auth),ids);
        return Map.of("sucesso",true);
    }
}
