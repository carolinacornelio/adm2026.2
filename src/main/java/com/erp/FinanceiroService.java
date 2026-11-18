package com.erp;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Contas a pagar e a receber. Regras:
 * <ul>
 *   <li>valores sempre BigDecimal, positivos, até 2 casas e no máximo 99.999.999,99;</li>
 *   <li>exclusão é lógica: lançamento pendente vira "cancelado"; liquidado não pode ser cancelado;</li>
 *   <li>cada compra/venda gera no máximo um lançamento (índice único + ON CONFLICT DO NOTHING);</li>
 *   <li>liquidar usa UPDATE condicionado a status pendente, então repetir a chamada não duplica nem sobrescreve;</li>
 *   <li>lançamentos automáticos (compra/venda/POS) não têm o valor editável, para não divergir da origem.</li>
 * </ul>
 */
@Service
public class FinanceiroService {
    static final BigDecimal LIMITE = new BigDecimal("99999999.99");

    /** Tabelas e colunas são fixas aqui; NENHUM texto do usuário é concatenado em SQL. */
    enum Tipo {
        PAGAR("contas_pagar", "compra_id", "data_pagamento", "pago", "valor_pago", "COMPRA"),
        RECEBER("contas_receber", "venda_id", "data_recebimento", "recebido", "valor_recebido", "VENDA");
        final String tabela, ligacao, dataLiquidacao, statusLiquidado, valorLiquidado, origemAutomatica;
        Tipo(String t, String l, String d, String s, String v, String o) { tabela = t; ligacao = l; dataLiquidacao = d; statusLiquidado = s; valorLiquidado = v; origemAutomatica = o; }
        static Tipo de(String texto) {
            if ("pagar".equalsIgnoreCase(texto)) return PAGAR;
            if ("receber".equalsIgnoreCase(texto)) return RECEBER;
            throw new IllegalArgumentException("Tipo inválido. Use pagar ou receber.");
        }
    }
    static final Set<String> SITUACOES = Set.of("PENDENTE", "VENCIDO", "LIQUIDADO", "CANCELADO");

    private final JdbcTemplate db;
    public FinanceiroService(JdbcTemplate db) { this.db = db; }

    // ---------------- consultas ----------------

    private static String select(Tipo t) {
        return "SELECT c.id, c.descricao, c.valor, c.vencimento, c.status, "
                + "CASE WHEN c.status = 'cancelado' THEN 'cancelado' WHEN c.status = 'pendente' AND c.vencimento < ? THEN 'vencido' ELSE c.status END AS situacao, "
                + "c." + t.dataLiquidacao + " AS \"dataLiquidacao\", c." + t.valorLiquidado + " AS \"valorLiquidado\", "
                + "c.categoria_id AS \"categoriaId\", cf.nome AS \"categoriaNome\", c.origem, c." + t.ligacao + " AS \"origemId\", "
                + "c.observacao, c.created_at AS \"criadoEm\" "
                + "FROM " + t.tabela + " c LEFT JOIN categorias_financeiras cf ON cf.id = c.categoria_id AND cf.empresa_id = c.empresa_id ";
    }

    public List<Map<String, Object>> listar(int empresa, Tipo t, String situacao, LocalDate de, LocalDate ate, Integer categoriaId, String busca) {
        LocalDate hoje = InventarioService.hoje();
        StringBuilder sql = new StringBuilder(select(t) + "WHERE c.empresa_id = ?");
        List<Object> p = new ArrayList<>(List.of(hoje, empresa));
        if (situacao != null && !situacao.isBlank()) {
            switch (Dados.opcao(situacao, SITUACOES, "situacao")) {
                case "PENDENTE" -> { sql.append(" AND c.status = 'pendente' AND c.vencimento >= ?"); p.add(hoje); }
                case "VENCIDO" -> { sql.append(" AND c.status = 'pendente' AND c.vencimento < ?"); p.add(hoje); }
                case "LIQUIDADO" -> sql.append(" AND c.status = '" + t.statusLiquidado + "'");
                default -> sql.append(" AND c.status = 'cancelado'");
            }
        }
        if (de != null) { sql.append(" AND c.vencimento >= ?"); p.add(de); }
        if (ate != null) { sql.append(" AND c.vencimento <= ?"); p.add(ate); }
        if (categoriaId != null) { sql.append(" AND c.categoria_id = ?"); p.add(categoriaId); }
        if (busca != null && !busca.isBlank()) { sql.append(" AND LOWER(c.descricao) LIKE ?"); p.add("%" + busca.trim().toLowerCase(Locale.ROOT) + "%"); }
        sql.append(" ORDER BY c.vencimento, c.id LIMIT 500");
        return db.queryForList(sql.toString(), p.toArray());
    }

    public Map<String, Object> buscar(int empresa, Tipo t, int id) {
        Map<String, Object> conta = conta(empresa, t, id);
        conta.put("historico", db.queryForList("""
                SELECT h.acao, h.valor, h.detalhe, h.usuario_id AS "usuarioId", u.nome AS "usuarioNome", h.created_at AS "criadoEm"
                  FROM financeiro_historico h LEFT JOIN usuarios u ON u.id = h.usuario_id AND u.empresa_id = h.empresa_id
                 WHERE h.empresa_id = ? AND h.tipo = ? AND h.conta_id = ? ORDER BY h.id DESC
                """, empresa, t.name(), id));
        return conta;
    }

    /** Totais a pagar/receber, vencidos e liquidados no mês corrente, e o saldo previsto. */
    public Map<String, Object> resumo(int empresa) {
        LocalDate hoje = InventarioService.hoje(), ini = hoje.withDayOfMonth(1), fim = hoje.withDayOfMonth(hoje.lengthOfMonth());
        Map<String, Object> r = new LinkedHashMap<>();
        for (Tipo t : Tipo.values()) {
            r.put(t.name().toLowerCase(Locale.ROOT), db.queryForMap(
                    "SELECT COALESCE(SUM(valor) FILTER (WHERE status = 'pendente'), 0) AS pendente, "
                    + "COUNT(*) FILTER (WHERE status = 'pendente') AS \"quantidadePendente\", "
                    + "COALESCE(SUM(valor) FILTER (WHERE status = 'pendente' AND vencimento < ?), 0) AS vencido, "
                    + "COUNT(*) FILTER (WHERE status = 'pendente' AND vencimento < ?) AS \"quantidadeVencida\", "
                    + "COALESCE(SUM(COALESCE(" + t.valorLiquidado + ", valor)) FILTER (WHERE status = '" + t.statusLiquidado + "' AND " + t.dataLiquidacao + " BETWEEN ? AND ?), 0) AS \"liquidadoNoMes\" "
                    + "FROM " + t.tabela + " WHERE empresa_id = ?", hoje, hoje, ini, fim, empresa));
        }
        BigDecimal receber = (BigDecimal) ((Map<?, ?>) r.get("receber")).get("pendente"), pagar = (BigDecimal) ((Map<?, ?>) r.get("pagar")).get("pendente");
        r.put("saldoPrevisto", receber.subtract(pagar));
        return r;
    }

    // ---------------- escrita ----------------

    @Transactional
    public Map<String, Object> criar(Autorizacao.Contexto c, Tipo t, Map<String, Object> d) {
        BigDecimal valor = valor(d, true);
        Integer categoria = Dados.inteiro(d, "categoriaId", false);
        validarCategoria(c.empresaId(), t, categoria);
        int id = db.queryForObject("INSERT INTO " + t.tabela + "(empresa_id, descricao, valor, vencimento, status, categoria_id, observacao, origem) VALUES (?,?,?,?,'pendente',?,?,'MANUAL') RETURNING id",
                Integer.class, c.empresaId(), Dados.texto(d, "descricao", 255, true), valor, Dados.data(d, "vencimento", true), categoria, Dados.texto(d, "observacao", 500, false));
        historico(c.empresaId(), t, id, "CRIADO", valor, "", c.usuarioId());
        return buscar(c.empresaId(), t, id);
    }

    @Transactional
    public Map<String, Object> atualizar(Autorizacao.Contexto c, Tipo t, int id, Map<String, Object> d) {
        Map<String, Object> atual = conta(c.empresaId(), t, id);
        if (!"pendente".equals(atual.get("status"))) throw new IllegalArgumentException("Somente lançamentos pendentes podem ser editados.");
        boolean automatico = !"MANUAL".equals(atual.get("origem"));
        BigDecimal valor = (BigDecimal) atual.get("valor");
        if (d.containsKey("valor")) {
            BigDecimal novo = valor(d, true);
            if (automatico && novo.compareTo(valor) != 0) throw new IllegalArgumentException("O valor de lançamentos gerados por compras e vendas não pode ser alterado.");
            valor = novo;
        }
        Integer categoria = d.containsKey("categoriaId") ? Dados.inteiro(d, "categoriaId", false) : (Integer) atual.get("categoriaId");
        validarCategoria(c.empresaId(), t, categoria);
        LocalDate vencimento = d.containsKey("vencimento") ? Dados.data(d, "vencimento", true) : ((java.sql.Date) atual.get("vencimento")).toLocalDate();
        db.update("UPDATE " + t.tabela + " SET descricao=?, valor=?, vencimento=?, categoria_id=?, observacao=?, updated_at=NOW() WHERE id=? AND empresa_id=? AND status='pendente'",
                d.containsKey("descricao") ? Dados.texto(d, "descricao", 255, true) : atual.get("descricao"), valor, vencimento, categoria,
                d.containsKey("observacao") ? Dados.texto(d, "observacao", 500, false) : atual.get("observacao"), id, c.empresaId());
        historico(c.empresaId(), t, id, "EDITADO", valor, "", c.usuarioId());
        return buscar(c.empresaId(), t, id);
    }

    /** Paga ou recebe o valor integral. Condicionado a status pendente: repetir a chamada falha sem alterar nada. */
    @Transactional
    public Map<String, Object> liquidar(Autorizacao.Contexto c, Tipo t, int id, Map<String, Object> d) {
        LocalDate data = d.get("data") == null || d.get("data").toString().isBlank() ? InventarioService.hoje() : Dados.data(d, "data", true);
        if (data.isAfter(InventarioService.hoje())) throw new IllegalArgumentException("A data de liquidação não pode ser futura.");
        int alteradas = db.update("UPDATE " + t.tabela + " SET status=?, " + t.dataLiquidacao + "=?, " + t.valorLiquidado + "=valor, updated_at=NOW() WHERE id=? AND empresa_id=? AND status='pendente'",
                t.statusLiquidado, data, id, c.empresaId());
        if (alteradas == 0) {
            conta(c.empresaId(), t, id); // 404 se for de outra empresa ou inexistente
            throw new IllegalArgumentException("Este lançamento já foi liquidado ou está cancelado.");
        }
        historico(c.empresaId(), t, id, t.statusLiquidado.toUpperCase(Locale.ROOT), (BigDecimal) conta(c.empresaId(), t, id).get("valor"), "Em " + data, c.usuarioId());
        return buscar(c.empresaId(), t, id);
    }

    /** Exclusão lógica. Lançamento já liquidado não pode ser cancelado. */
    @Transactional
    public Map<String, Object> cancelar(Autorizacao.Contexto c, Tipo t, int id, Map<String, Object> d) {
        int alteradas = db.update("UPDATE " + t.tabela + " SET status='cancelado', cancelado_em=NOW(), updated_at=NOW() WHERE id=? AND empresa_id=? AND status='pendente'", id, c.empresaId());
        if (alteradas == 0) {
            conta(c.empresaId(), t, id);
            throw new IllegalArgumentException("Somente lançamentos pendentes podem ser cancelados.");
        }
        historico(c.empresaId(), t, id, "CANCELADO", null, Dados.texto(d, "motivo", 250, false), c.usuarioId());
        return buscar(c.empresaId(), t, id);
    }

    // ---------------- integração com compras e vendas ----------------

    /** Chamado na MESMA transação da compra. Idempotente por compra. */
    void gerarParaCompra(int empresa, int compraId, BigDecimal total, LocalDate vencimento, boolean liquidado, int usuario) {
        gerar(empresa, Tipo.PAGAR, compraId, "Compra #" + compraId, total, vencimento, liquidado, usuario);
    }

    /** Chamado na MESMA transação da venda. Idempotente por venda. */
    void gerarParaVenda(int empresa, int vendaId, BigDecimal total, LocalDate vencimento, boolean liquidado, int usuario) {
        gerar(empresa, Tipo.RECEBER, vendaId, "Venda #" + vendaId, total, vencimento, liquidado, usuario);
    }

    private void gerar(int empresa, Tipo t, int origemId, String descricao, BigDecimal total, LocalDate vencimento, boolean liquidado, int usuario) {
        if (total == null || total.signum() <= 0) return;
        LocalDate hoje = InventarioService.hoje();
        String status = liquidado ? t.statusLiquidado : "pendente";
        Integer id = db.queryForList("INSERT INTO " + t.tabela + "(empresa_id, " + t.ligacao + ", descricao, valor, vencimento, status, " + t.dataLiquidacao + ", " + t.valorLiquidado + ", origem) "
                + "VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (empresa_id, " + t.ligacao + ") WHERE " + t.ligacao + " IS NOT NULL DO NOTHING RETURNING id",
                Integer.class, empresa, origemId, descricao, total, vencimento, status, liquidado ? hoje : null, liquidado ? total : null, t.origemAutomatica)
                .stream().findFirst().orElse(null);
        if (id != null) historico(empresa, t, id, "GERADO", total, "Origem: " + descricao, usuario);
    }

    // ---------------- categorias ----------------

    public List<Map<String, Object>> categorias(int empresa, String tipo) {
        if (tipo == null || tipo.isBlank())
            return db.queryForList("SELECT id, nome, tipo, ativo FROM categorias_financeiras WHERE empresa_id=? ORDER BY tipo, LOWER(nome)", empresa);
        return db.queryForList("SELECT id, nome, tipo, ativo FROM categorias_financeiras WHERE empresa_id=? AND tipo=? ORDER BY LOWER(nome)", empresa, Tipo.de(tipo).name());
    }

    @Transactional
    public Map<String, Object> criarCategoria(int empresa, Map<String, Object> d) {
        Tipo t = Tipo.de(Dados.texto(d, "tipo", 10, true));
        try {
            int id = db.queryForObject("INSERT INTO categorias_financeiras(empresa_id, nome, tipo) VALUES (?,?,?) RETURNING id", Integer.class, empresa, Dados.texto(d, "nome", 80, true), t.name());
            return db.queryForMap("SELECT id, nome, tipo, ativo FROM categorias_financeiras WHERE id=? AND empresa_id=?", id, empresa);
        } catch (DuplicateKeyException e) { throw new IllegalArgumentException("Já existe uma categoria com este nome."); }
    }

    @Transactional
    public Map<String, Object> atualizarCategoria(int empresa, int id, Map<String, Object> d) {
        List<Map<String, Object>> r = db.queryForList("SELECT id, nome, ativo FROM categorias_financeiras WHERE id=? AND empresa_id=?", id, empresa);
        if (r.isEmpty()) throw new NaoEncontradoException("Categoria não encontrada.");
        try {
            db.update("UPDATE categorias_financeiras SET nome=?, ativo=? WHERE id=? AND empresa_id=?",
                    d.containsKey("nome") ? Dados.texto(d, "nome", 80, true) : r.get(0).get("nome"),
                    d.get("ativo") instanceof Boolean b ? b : r.get(0).get("ativo"), id, empresa);
        } catch (DuplicateKeyException e) { throw new IllegalArgumentException("Já existe uma categoria com este nome."); }
        return db.queryForMap("SELECT id, nome, tipo, ativo FROM categorias_financeiras WHERE id=? AND empresa_id=?", id, empresa);
    }

    // ---------------- internos ----------------

    private Map<String, Object> conta(int empresa, Tipo t, int id) {
        List<Map<String, Object>> r = db.queryForList(select(t) + "WHERE c.empresa_id = ? AND c.id = ?", InventarioService.hoje(), empresa, id);
        if (r.isEmpty()) throw new NaoEncontradoException("Lançamento não encontrado.");
        return r.get(0);
    }

    private void validarCategoria(int empresa, Tipo t, Integer categoria) {
        if (categoria == null) return;
        if (db.queryForObject("SELECT COUNT(*) FROM categorias_financeiras WHERE id=? AND empresa_id=? AND tipo=? AND ativo", Integer.class, categoria, empresa, t.name()) == 0)
            throw new IllegalArgumentException("Categoria financeira inválida para este tipo de lançamento.");
    }

    private static BigDecimal valor(Map<String, Object> d, boolean obrigatorio) {
        BigDecimal v = Dados.decimal(d, "valor", obrigatorio);
        if (v == null) return null;
        if (v.signum() <= 0) throw new IllegalArgumentException("O valor deve ser maior que zero.");
        if (v.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("O valor aceita no máximo duas casas decimais.");
        if (v.compareTo(LIMITE) > 0) throw new IllegalArgumentException("O valor excede o máximo permitido (99.999.999,99).");
        return v.setScale(2);
    }

    private void historico(int empresa, Tipo t, int conta, String acao, BigDecimal valor, String detalhe, Integer usuario) {
        db.update("INSERT INTO financeiro_historico(empresa_id, tipo, conta_id, acao, valor, detalhe, usuario_id) VALUES (?,?,?,?,?,?,?)", empresa, t.name(), conta, acao, valor, detalhe, usuario);
    }
}
