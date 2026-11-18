package com.erp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Central de Atendimento: chamados de suporte isolados por empresa (multi-tenant). */
@RestController
@RequestMapping("/api/chamados")
public class ChamadoController {

    private static final Set<String> SEVERIDADES = Set.of("critica", "alta", "media");
    private static final Set<String> SITUACOES = Set.of("Aberto", "Em atendimento", "Resolvido", "Cancelado");

    // criadoEm sai como texto ISO em UTC (ex.: 2026-10-14T20:03:51Z); o navegador converte para o fuso local.
    private static final String SELECT =
            "SELECT id, cliente, titulo, descricao, severidade, situacao, "
            + "to_char(criado_em AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS \"criadoEm\" "
            + "FROM chamados ";

    private final JdbcTemplate jdbc;
    private final CurrentUser user;

    public ChamadoController(JdbcTemplate jdbc, CurrentUser user) {
        this.jdbc = jdbc;
        this.user = user;
    }

    @GetMapping
    public List<Map<String, Object>> listar(@RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        return jdbc.queryForList(SELECT + "WHERE empresa_id = ? ORDER BY id DESC", empresaId);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> criar(@RequestBody Map<String, Object> dados,
                                     @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);

        String cliente = obrigatorio(dados, "cliente", "Informe o cliente.", 120);
        String titulo = obrigatorio(dados, "titulo", "Informe o título do chamado.", 160);
        String descricao = obrigatorio(dados, "descricao", "Informe a descrição do chamado.", 2000);
        String severidade = String.valueOf(dados.get("severidade"));
        if (!SEVERIDADES.contains(severidade)) {
            throw new IllegalArgumentException("Severidade inválida.");
        }

        Number id = jdbc.queryForObject(
                "INSERT INTO chamados (empresa_id, cliente, titulo, descricao, severidade) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Number.class, empresaId, cliente, titulo, descricao, severidade);

        return jdbc.queryForMap(SELECT + "WHERE id = ? AND empresa_id = ?", id.longValue(), empresaId);
    }

    @PutMapping("/{id}/situacao")
    @Transactional
    public Map<String, Object> atualizarSituacao(@PathVariable int id,
            @RequestBody Map<String, Object> dados,
            @RequestHeader("Authorization") String authorization) {
        int empresaId = user.empresa(authorization);
        String situacao = String.valueOf(dados.get("situacao"));
        if (!SITUACOES.contains(situacao)) throw new IllegalArgumentException("Situação inválida.");
        if (jdbc.update("UPDATE chamados SET situacao=? WHERE id=? AND empresa_id=?", situacao, id, empresaId) != 1)
            throw new IllegalArgumentException("Chamado não encontrado.");
        return jdbc.queryForMap(SELECT + "WHERE id=? AND empresa_id=?", id, empresaId);
    }

    private static String obrigatorio(Map<String, Object> dados, String chave, String mensagem, int max) {
        Object valor = dados.get(chave);
        String texto = valor == null ? "" : valor.toString().trim();
        if (texto.isEmpty()) {
            throw new IllegalArgumentException(mensagem);
        }
        if (texto.length() > max) {
            throw new IllegalArgumentException("Um dos campos excede o tamanho permitido.");
        }
        return texto;
    }
}
