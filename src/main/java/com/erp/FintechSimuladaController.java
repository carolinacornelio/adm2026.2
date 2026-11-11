package com.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Fintech simulada: faz o papel do outro sistema. Monta o evento de pagamento, assina com o segredo
 * compartilhado e chama o webhook do ERP por HTTP. Os modos criam falhas propositais para os testes.
 */
@RestController
@RequestMapping("/api/fintech-sim")
public class FintechSimuladaController {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final JdbcTemplate jdbc;
    private final CurrentUser user;
    private final ObjectMapper json;

    @Value("${fintech.webhook.secret:segredo-de-desenvolvimento-trocar}")
    private String segredo;

    @Value("${server.port:8080}")
    private int porta;

    public FintechSimuladaController(JdbcTemplate jdbc, CurrentUser user, ObjectMapper json) {
        this.jdbc = jdbc;
        this.user = user;
        this.json = json;
    }

    @PostMapping("/cobrancas/{codigo}/simular")
    public Map<String, Object> simular(@PathVariable String codigo,
                                       @RequestParam(defaultValue = "normal") String modo,
                                       @RequestHeader("Authorization") String authorization) throws Exception {
        int empresaId = user.empresa(authorization);
        List<Map<String, Object>> cobrancas = jdbc.queryForList(
                "SELECT valor FROM fintech_cobrancas WHERE codigo_externo = ? AND empresa_id = ?", codigo, empresaId);
        if (cobrancas.isEmpty()) throw new IllegalArgumentException("Cobrança não encontrada.");

        BigDecimal valor = (BigDecimal) cobrancas.get(0).get("valor");
        if ("valor-divergente".equals(modo)) valor = valor.add(BigDecimal.ONE);

        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("eventoId", "evt-" + codigo + "-pago");   // mesmo id em reenvios: base da idempotência
        evento.put("tipo", "cobranca.paga");
        evento.put("cobrancaCodigo", codigo);
        evento.put("valor", valor);
        evento.put("pagoEm", Instant.now().toString());
        String corpo = json.writeValueAsString(evento);

        long carimbo = Instant.now().getEpochSecond() - ("timestamp-antigo".equals(modo) ? 600 : 0);
        String chave = "assinatura-invalida".equals(modo) ? "segredo-errado" : segredo;
        String assinatura = FintechAssinatura.assinar(chave, String.valueOf(carimbo), corpo);

        HttpRequest requisicao = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + porta + "/api/integracoes/fintech/webhook"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Fintech-Timestamp", String.valueOf(carimbo))
                .header("X-Fintech-Signature", assinatura)
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        HttpResponse<String> resposta = HTTP.send(requisicao, HttpResponse.BodyHandlers.ofString());
        return Map.of("modo", modo, "statusHttp", resposta.statusCode(), "resposta", resposta.body());
    }
}
