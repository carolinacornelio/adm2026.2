package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Relatório de comparação de preços e saída recente por produto. */
@RestController
@RequestMapping("/api/concorrencia")
public class ConcorrenciaController {
    private final JdbcTemplate db;
    private final CurrentUser user;

    public ConcorrenciaController(JdbcTemplate db, CurrentUser user) {
        this.db = db;
        this.user = user;
    }

    @GetMapping
    public List<Map<String, Object>> listar(@RequestHeader("Authorization") String authorization) {
        int empresa = user.empresa(authorization);
        return db.queryForList("""
                SELECT p.id, p.nome,
                       COALESCE(p.preco_venda, p.preco) AS "nossoPreco",
                       p.preco_concorrente AS "precoConcorrente",
                       CASE WHEN p.preco_concorrente IS NULL OR p.preco_concorrente = 0 THEN NULL
                            ELSE ROUND(((COALESCE(p.preco_venda, p.preco) - p.preco_concorrente)
                                      / p.preco_concorrente * 100)::numeric, 2)
                       END AS "diferencaPercentual",
                       COALESCE((SELECT SUM(iv.quantidade)
                                   FROM itens_venda iv
                                   JOIN vendas v ON v.id = iv.venda_id
                                  WHERE iv.produto_id = p.id
                                    AND v.empresa_id = p.empresa_id
                                    AND v.status = 'concluida'
                                    AND v.data >= CURRENT_TIMESTAMP - INTERVAL '30 days'), 0) AS "vendidos30Dias"
                  FROM produtos p
                 WHERE p.empresa_id = ? AND COALESCE(p.ativo, true) = true
                 ORDER BY p.nome, p.id
                """, empresa);
    }

    @PutMapping("/{produtoId}")
    @Transactional
    public Map<String, Object> salvar(@PathVariable int produtoId,
                                      @RequestBody Map<String, Object> dados,
                                      @RequestHeader("Authorization") String authorization) {
        int empresa = user.empresa(authorization);
        BigDecimal preco = precoConcorrente(dados.get("precoConcorrente"));
        if (db.update("UPDATE produtos SET preco_concorrente=? WHERE id=? AND empresa_id=? AND ativo=true",
                preco, produtoId, empresa) != 1) {
            throw new IllegalArgumentException("Produto não encontrado.");
        }
        return listar(authorization).stream()
                .filter(item -> ((Number) item.get("id")).intValue() == produtoId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado."));
    }

    private BigDecimal precoConcorrente(Object valor) {
        if (valor == null || valor.toString().isBlank()) return null;
        try {
            BigDecimal preco = new BigDecimal(valor.toString().replace(',', '.'))
                    .setScale(2, RoundingMode.UNNECESSARY);
            if (preco.signum() < 0 || preco.compareTo(new BigDecimal("99999999.99")) > 0) {
                throw new NumberFormatException();
            }
            return preco;
        } catch (ArithmeticException | NumberFormatException erro) {
            throw new IllegalArgumentException("Preço do concorrente inválido. Informe até duas casas decimais.");
        }
    }
}
