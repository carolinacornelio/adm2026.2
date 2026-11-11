package com.erp;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FintechController {

    private final FintechService service;
    private final CurrentUser user;

    public FintechController(FintechService service, CurrentUser user) {
        this.service = service;
        this.user = user;
    }

    @GetMapping("/financeiro/cobrancas")
    public List<Map<String, Object>> contas(@RequestHeader("Authorization") String authorization) {
        return service.listarContas(user.empresa(authorization));
    }

    @PostMapping("/financeiro/cobrancas")
    public Map<String, Object> criarCobranca(@RequestBody Map<String, Object> corpo,
                                             @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        int contaId;
        try {
            contaId = Integer.parseInt(String.valueOf(corpo.get("contaReceberId")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Informe a conta a receber.");
        }
        return service.criarCobranca(empresaId, contaId);
    }

    @GetMapping("/financeiro/auditoria")
    public List<Map<String, Object>> auditoria(@RequestParam(required = false) String resultado,
                                               @RequestParam(required = false) String data,
                                               @RequestHeader("Authorization") String authorization) {
        return service.listarAuditoria(user.empresa(authorization), resultado, data);
    }

    @GetMapping("/financeiro/resumo")
    public Map<String, Object> resumo(@RequestHeader("Authorization") String authorization) {
        return service.resumo(user.empresa(authorization));
    }

    /** Gera a cobrança Pix da conta a receber de uma venda (o POS chama ao finalizar uma venda em Pix). */
    @PostMapping("/financeiro/cobrancas/venda")
    public Map<String, Object> cobrancaDaVenda(@RequestBody Map<String, Object> corpo,
                                               @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        int vendaId;
        try {
            vendaId = Integer.parseInt(String.valueOf(corpo.get("vendaId")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Informe a venda.");
        }
        return service.criarCobrancaDaVenda(empresaId, vendaId);
    }

    /**
     * Endpoint chamado pela fintech. Não usa o token do usuário: a autenticidade vem da assinatura
     * HMAC e do carimbo de tempo enviados nos cabeçalhos.
     */
    @PostMapping("/integracoes/fintech/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestBody String corpo,
            @RequestHeader(value = "X-Fintech-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Fintech-Signature", required = false) String assinatura) {
        FintechService.Resposta resposta = service.processarWebhook(corpo, timestamp, assinatura);
        return ResponseEntity.status(resposta.http())
                .body(Map.of("resultado", resposta.resultado(), "mensagem", resposta.mensagem()));
    }
}
