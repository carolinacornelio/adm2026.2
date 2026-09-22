package com.erp;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Endpoint do Assistente IA; permanece somente de leitura. */
@RestController
@RequestMapping("/api/ia")
public class IAController {
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
        return Map.of("resposta", iaService.responder(empresaId, dados));
    }
}
