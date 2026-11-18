package com.erp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pos")
public class PosController {
    private final PosService service;
    private final CurrentUser user;
    private final PosRealtimeService realtime;
    public PosController(PosService service, CurrentUser user, PosRealtimeService realtime) {
        this.service = service; this.user = user; this.realtime = realtime;
    }

    @GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventos(@RequestHeader("Authorization") String authorization) {
        return realtime.assinar(user.empresa(authorization));
    }

    @PostMapping("/vendas")
    public Map<String, Object> finalizar(@Valid @RequestBody Venda pedido,
                                         @RequestHeader("Authorization") String authorization) {
        return service.finalizar(pedido, user.empresa(authorization), user.usuario(authorization));
    }
    @GetMapping("/vendas")
    public List<Map<String, Object>> listar(@RequestHeader("Authorization") String authorization) {
        return service.listar(user.empresa(authorization));
    }
    @GetMapping("/vendas/{id}")
    public Map<String, Object> buscar(@PathVariable int id, @RequestHeader("Authorization") String authorization) {
        return service.buscar(id, user.empresa(authorization));
    }
    public record Item(@NotNull @Positive Integer produtoId, @NotNull @Min(1) @Max(1000000) Integer quantidade) {}
    public record Venda(
            @NotNull UUID chaveOperacao,
            @Positive Integer clienteId,
            @NotEmpty @Size(max = 200) List<@NotNull @Valid Item> itens,
            @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") @Digits(integer = 8, fraction = 2) BigDecimal desconto,
            @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") @Digits(integer = 8, fraction = 2) BigDecimal totalEsperado,
            @NotNull @Pattern(regexp = "DINHEIRO|PIX|CREDITO|DEBITO") String formaPagamento,
            @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") @Digits(integer = 8, fraction = 2) BigDecimal valorRecebido,
            @NotNull @AssertTrue Boolean pagamentoConfirmado) {}
}
