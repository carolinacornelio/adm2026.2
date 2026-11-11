package com.erp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Integração com a fintech: cobranças Pix e confirmação de pagamento por webhook assinado. */
@Service
public class FintechService {

    private static final long JANELA_SEGUNDOS = 300;

    public record Resposta(int http, String resultado, String mensagem) {
    }

    private final JdbcTemplate jdbc;
    private final PosRealtimeService realtime;
    private final ObjectMapper json;

    @Value("${fintech.webhook.secret:segredo-de-desenvolvimento-trocar}")
    private String segredo;

    public FintechService(JdbcTemplate jdbc, PosRealtimeService realtime, ObjectMapper json) {
        this.jdbc = jdbc;
        this.realtime = realtime;
        this.json = json;
    }

    public List<Map<String, Object>> listarContas(int empresaId) {
        return jdbc.queryForList(
                "SELECT cr.id AS \"contaId\", cr.descricao, cr.valor, cr.vencimento, cr.status AS \"statusConta\", "
                        + "fc.codigo_externo AS codigo, fc.status AS \"statusCobranca\" "
                        + "FROM contas_receber cr "
                        + "LEFT JOIN fintech_cobrancas fc ON fc.conta_receber_id = cr.id AND fc.status <> 'cancelada' "
                        + "WHERE cr.empresa_id = ? ORDER BY cr.id DESC LIMIT 50", empresaId);
    }

    /** Trilha de auditoria da empresa, com filtro opcional por resultado e por dia (horário de Brasília). */
    public List<Map<String, Object>> listarAuditoria(int empresaId, String resultado, String data) {
        StringBuilder sql = new StringBuilder(
                "SELECT evento_id AS \"eventoId\", cobranca_codigo AS codigo, resultado, motivo, "
                        + "to_char(recebido_em AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS \"recebidoEm\" "
                        + "FROM fintech_auditoria WHERE empresa_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(empresaId);
        if (resultado != null && !resultado.isBlank()) {
            sql.append(" AND resultado = ?");
            args.add(resultado.trim());
        }
        if (data != null && !data.isBlank()) {
            try {
                args.add(java.sql.Date.valueOf(LocalDate.parse(data.trim())));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Data inválida. Use o formato AAAA-MM-DD.");
            }
            sql.append(" AND (recebido_em AT TIME ZONE 'America/Sao_Paulo')::date = ?");
        }
        sql.append(" ORDER BY id DESC LIMIT 50");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Números dos cartões da tela Financeiro. */
    public Map<String, Object> resumo(int empresaId) {
        String hoje = "(NOW() AT TIME ZONE 'America/Sao_Paulo')::date";
        return jdbc.queryForMap(
                "SELECT "
                        + "(SELECT COALESCE(SUM(valor), 0) FROM contas_receber WHERE empresa_id = ? AND LOWER(status) <> 'pago') AS pendente, "
                        + "(SELECT COUNT(*) FROM contas_receber WHERE empresa_id = ? AND LOWER(status) <> 'pago') AS \"qtdPendentes\", "
                        + "(SELECT COALESCE(SUM(valor), 0) FROM fintech_cobrancas WHERE empresa_id = ? AND status = 'paga' "
                        + "AND (pago_em AT TIME ZONE 'America/Sao_Paulo')::date = " + hoje + ") AS \"liquidadoHoje\", "
                        + "(SELECT COUNT(*) FROM fintech_cobrancas WHERE empresa_id = ? AND status = 'pendente') AS aguardando, "
                        + "(SELECT COUNT(*) FROM fintech_auditoria WHERE empresa_id = ? AND resultado = 'rejeitado' "
                        + "AND (recebido_em AT TIME ZONE 'America/Sao_Paulo')::date = " + hoje + ") AS \"rejeitadosHoje\"",
                empresaId, empresaId, empresaId, empresaId, empresaId);
    }

    /** Gera a cobrança Pix da conta a receber de uma venda (usado pelo POS ao finalizar uma venda em Pix). */
    @Transactional
    public Map<String, Object> criarCobrancaDaVenda(int empresaId, int vendaId) {
        List<Integer> contas = jdbc.queryForList(
                "SELECT id FROM contas_receber WHERE venda_id = ? AND empresa_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class, vendaId, empresaId);
        if (contas.isEmpty()) throw new IllegalArgumentException("Esta venda não tem conta a receber.");
        return criarCobranca(empresaId, contas.get(0));
    }

    /** Cria a cobrança Pix de uma conta a receber. Repetir a chamada devolve a mesma cobrança pendente. */
    @Transactional
    public Map<String, Object> criarCobranca(int empresaId, int contaId) {
        List<Map<String, Object>> contas = jdbc.queryForList(
                "SELECT id, valor, status FROM contas_receber WHERE id = ? AND empresa_id = ? FOR UPDATE",
                contaId, empresaId);
        if (contas.isEmpty()) throw new IllegalArgumentException("Conta a receber não encontrada.");

        Map<String, Object> conta = contas.get(0);
        if ("pago".equalsIgnoreCase(texto(conta.get("status")))) {
            throw new IllegalArgumentException("Esta conta já está paga.");
        }
        BigDecimal valor = (BigDecimal) conta.get("valor");
        if (valor == null || valor.signum() <= 0) throw new IllegalArgumentException("Valor inválido para cobrança.");

        List<Map<String, Object>> abertas = jdbc.queryForList(
                "SELECT codigo_externo AS codigo, valor, status FROM fintech_cobrancas "
                        + "WHERE conta_receber_id = ? AND status = 'pendente'", contaId);
        if (!abertas.isEmpty()) return abertas.get(0);

        String codigo = "FT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        jdbc.update("INSERT INTO fintech_cobrancas (empresa_id, conta_receber_id, codigo_externo, valor) "
                + "VALUES (?, ?, ?, ?)", empresaId, contaId, codigo, valor);
        registrar(empresaId, null, codigo, "criada", "Cobrança Pix criada para a conta " + contaId + ".");
        realtime.publicarDepoisDoCommit(empresaId, "financeiro-atualizado");
        return Map.of("codigo", codigo, "valor", valor, "status", "pendente");
    }

    /**
     * Recebe o webhook da fintech. Ordem das defesas: carimbo de tempo, assinatura, conteúdo,
     * cobrança existente, evento repetido e valor. Toda tentativa fica na trilha de auditoria.
     */
    @Transactional
    public Resposta processarWebhook(String corpo, String timestamp, String assinatura) {
        Map<String, Object> dados = lerCorpo(corpo);
        String codigo = texto(dados.get("cobrancaCodigo"));
        String eventoId = texto(dados.get("eventoId"));
        String carimboTexto = texto(timestamp);

        long carimbo;
        try {
            carimbo = Long.parseLong(carimboTexto);
        } catch (NumberFormatException e) {
            return rejeitar(codigo, eventoId, 401, "Carimbo de tempo ausente ou inválido.");
        }
        if (Math.abs(Instant.now().getEpochSecond() - carimbo) > JANELA_SEGUNDOS) {
            return rejeitar(codigo, eventoId, 401, "Carimbo de tempo fora da janela permitida (possível reenvio).");
        }
        String recebida = texto(assinatura);
        if (recebida.isEmpty() || !FintechAssinatura.confere(segredo, carimboTexto, corpo, recebida)) {
            return rejeitar(codigo, eventoId, 401, "Assinatura inválida.");
        }
        if (codigo.isEmpty() || eventoId.isEmpty() || !"cobranca.paga".equals(texto(dados.get("tipo")))) {
            return rejeitar(codigo, eventoId, 400, "Evento inválido ou incompleto.");
        }

        BigDecimal informado;
        try {
            informado = new BigDecimal(texto(dados.get("valor")));
        } catch (NumberFormatException e) {
            return rejeitar(codigo, eventoId, 400, "Valor do evento inválido.");
        }
        return confirmarPagamento(codigo, eventoId, informado);
    }

    private Resposta confirmarPagamento(String codigo, String eventoId, BigDecimal informado) {
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT id, empresa_id, conta_receber_id, valor, status FROM fintech_cobrancas "
                        + "WHERE codigo_externo = ? FOR UPDATE", codigo);
        if (linhas.isEmpty()) return rejeitar(codigo, eventoId, 404, "Cobrança inexistente.");

        Map<String, Object> cobranca = linhas.get(0);
        int empresaId = ((Number) cobranca.get("empresa_id")).intValue();
        String status = texto(cobranca.get("status"));

        if ("paga".equals(status)) {
            registrar(empresaId, eventoId, codigo, "duplicado", "Evento repetido: a cobrança já estava paga.");
            return new Resposta(200, "duplicado", "Evento já processado.");
        }
        if (!"pendente".equals(status)) return rejeitar(codigo, eventoId, 409, "A cobrança não está pendente.");

        BigDecimal cobrado = (BigDecimal) cobranca.get("valor");
        if (cobrado.compareTo(informado) != 0) {
            return rejeitar(codigo, eventoId, 422, "Valor do evento diferente do valor cobrado.");
        }

        jdbc.update("UPDATE fintech_cobrancas SET status = 'paga', pago_em = NOW(), evento_pagamento_id = ? "
                + "WHERE id = ?", eventoId, cobranca.get("id"));
        int baixadas = jdbc.update("UPDATE contas_receber SET status = 'pago', data_recebimento = CURRENT_DATE "
                + "WHERE id = ? AND empresa_id = ? AND status <> 'pago'", cobranca.get("conta_receber_id"), empresaId);
        registrar(empresaId, eventoId, codigo, "aceito", "Pagamento confirmado; contas baixadas: " + baixadas + ".");
        realtime.publicarDepoisDoCommit(empresaId, "financeiro-atualizado");
        return new Resposta(200, "aceito", "Pagamento confirmado.");
    }

    private Resposta rejeitar(String codigo, String eventoId, int http, String motivo) {
        // A empresa só é conhecida se o código existir; a origem não autenticada serve apenas para a trilha.
        registrar(empresaDaCobranca(codigo), eventoId, codigo, "rejeitado", motivo);
        return new Resposta(http, "rejeitado", motivo);
    }

    private Integer empresaDaCobranca(String codigo) {
        if (codigo == null || codigo.isBlank()) return null;
        List<Integer> empresas = jdbc.queryForList(
                "SELECT empresa_id FROM fintech_cobrancas WHERE codigo_externo = ?", Integer.class, codigo);
        return empresas.isEmpty() ? null : empresas.get(0);
    }

    private void registrar(Integer empresaId, String eventoId, String codigo, String resultado, String motivo) {
        jdbc.update(
                "INSERT INTO fintech_auditoria (empresa_id, evento_id, cobranca_codigo, resultado, motivo) "
                        + "VALUES (?, ?, ?, ?, ?)",
                new Object[]{empresaId, nulo(eventoId), nulo(codigo), resultado, limitar(motivo)},
                new int[]{Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
    }

    private Map<String, Object> lerCorpo(String corpo) {
        try {
            return json.readValue(corpo, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static String texto(Object valor) {
        return valor == null ? "" : valor.toString().trim();
    }

    private static String nulo(String valor) {
        return valor == null || valor.isBlank() ? null : (valor.length() > 80 ? valor.substring(0, 80) : valor);
    }

    private static String limitar(String texto) {
        return texto != null && texto.length() > 255 ? texto.substring(0, 255) : texto;
    }
}
