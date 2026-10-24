package com.erp;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Endpoint do Assistente IA; permanece somente de leitura. */
@RestController
@RequestMapping("/api/ia")
public class IAController {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> falhaIA(org.springframework.web.server.ResponseStatusException erro) {
        return org.springframework.http.ResponseEntity.status(erro.getStatusCode())
                .body(Map.of("message", erro.getReason() == null ? "Não foi possível consultar a IA." : erro.getReason()));
    }
    private final CurrentUser user;
    private final PlanService plans;
    private final IAService iaService;

    public IAController(CurrentUser user, PlanService plans, IAService iaService) {
        this.user = user;
        this.plans = plans;
        this.iaService = iaService;
    }

    @PostMapping("/perguntar")
    public Map<String, Object> perguntar(
            @RequestBody IAPerguntaRequest dados,
            @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        plans.exigir(empresaId, "PLUS");
        return Map.of("resposta", iaService.responder(empresaId, user.usuario(authorization), dados));
    }
}
