package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assistente analítico do ERP.
 *
 * A primeira versão usa consultas determinísticas no PostgreSQL. Assim a
 * tela já funciona sem depender de uma chave de IA externa e cada resposta
 * permanece limitada à empresa do usuário autenticado.
 */
@RestController
@RequestMapping("/api/ia")
public class IAController {
    private final JdbcTemplate db;
    private final CurrentUser user;
    private final PlanService plans;

    public IAController(JdbcTemplate db, CurrentUser user, PlanService plans) {
        this.db = db;
        this.user = user;
        this.plans = plans;
    }

    /** Interpreta perguntas suportadas e consulta apenas o tenant logado. */
    @PostMapping("/perguntar")
    public Map<String, Object> perguntar(
            @RequestBody Map<String, Object> dados,
            @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        plans.exigir(empresaId, "PLUS");
        String pergunta = AuthService.texto(dados, "pergunta").toLowerCase(Locale.ROOT);

        if (pergunta.contains("faturamento")) {
            return Map.of("resposta", responderFaturamento(empresaId));
        }

        if (pergunta.contains("vendendo menos")
                || pergunta.contains("baixa saída")
                || pergunta.contains("baixa saida")) {
            return Map.of("resposta", responderBaixaSaida(empresaId));
        }

        if (pergunta.contains("estoque")
                && (pergunta.contains("baixo")
                || pergunta.contains("atenção")
                || pergunta.contains("atencao"))) {
            return Map.of("resposta", responderEstoqueBaixo(empresaId));
        }

        return Map.of("resposta",
                "O assistente está conectado ao ERP. Faça perguntas sobre estoque baixo, faturamento ou produtos com baixa saída.");
    }

    private String responderEstoqueBaixo(int empresaId) {
        List<Map<String, Object>> itens = db.queryForList("""
                SELECT p.nome,
                       COALESCE(e.quantidade, 0) AS quantidade,
                       COALESCE(e.estoque_minimo, 0) AS minimo
                  FROM produtos p
             LEFT JOIN estoque e ON e.produto_id = p.id
                               AND e.empresa_id = p.empresa_id
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
                   AND COALESCE(e.quantidade, 0) <= COALESCE(e.estoque_minimo, 0)
              ORDER BY quantidade, p.nome
                """, empresaId);

        if (itens.isEmpty()) return "Nenhum produto está abaixo ou no estoque mínimo.";
        return "Produtos que merecem atenção: " + resumo(itens, "nome", "quantidade");
    }

    private String responderBaixaSaida(int empresaId) {
        // O CASE impede que itens de outra empresa sejam somados caso exista
        // algum registro inconsistente no banco.
        List<Map<String, Object>> itens = db.queryForList("""
                SELECT p.nome,
                       COALESCE(SUM(CASE WHEN v.id IS NOT NULL THEN iv.quantidade ELSE 0 END), 0) AS quantidade
                  FROM produtos p
             LEFT JOIN itens_venda iv ON iv.produto_id = p.id
             LEFT JOIN vendas v ON v.id = iv.venda_id
                               AND v.empresa_id = ?
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
              GROUP BY p.id, p.nome
              ORDER BY quantidade, p.nome
                 LIMIT 5
                """, empresaId, empresaId);

        return "Produtos com menor saída registrada: " + resumo(itens, "nome", "quantidade");
    }

    private String responderFaturamento(int empresaId) {
        Map<String, Object> valores = db.queryForMap("""
                SELECT COALESCE(SUM(total) FILTER (
                           WHERE data >= date_trunc('month', CURRENT_DATE)
                             AND data < date_trunc('month', CURRENT_DATE) + INTERVAL '1 month'
                       ), 0) AS atual,
                       COALESCE(SUM(total) FILTER (
                           WHERE data >= date_trunc('month', CURRENT_DATE) - INTERVAL '1 month'
                             AND data < date_trunc('month', CURRENT_DATE)
                       ), 0) AS anterior
                  FROM vendas
                 WHERE empresa_id = ?
                """, empresaId);

        double atual = numero(valores.get("atual"));
        double anterior = numero(valores.get("anterior"));
        String variacao;

        if (anterior == 0) {
            variacao = atual == 0
                    ? "Não houve faturamento registrado neste mês nem no mês anterior."
                    : "Não é possível calcular a variação porque o mês anterior não teve faturamento.";
        } else {
            double percentual = ((atual - anterior) / anterior) * 100;
            variacao = String.format(Locale.US, "Variação de %+.2f%% em relação ao mês anterior.", percentual)
                    .replace('.', ',');
        }

        return String.format(Locale.US,
                        "Faturamento deste mês: R$ %.2f. Mês anterior: R$ %.2f. %s",
                        atual, anterior, variacao)
                .replace('.', ',');
    }

    private double numero(Object valor) {
        return valor instanceof Number numero ? numero.doubleValue() : 0d;
    }

    private String resumo(List<Map<String, Object>> linhas, String nome, String quantidade) {
        if (linhas.isEmpty()) return "nenhum dado encontrado.";
        return linhas.stream()
                .limit(5)
                .map(linha -> linha.get(nome) + " (" + linha.get(quantidade) + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("nenhum dado encontrado.");
    }
}
