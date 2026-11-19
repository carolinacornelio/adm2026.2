package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Objetivos e resultados-chave. Objetivos são ARQUIVADOS (nunca apagados) e resultados são CANCELADOS,
 * preservando o histórico. O progresso pode vir de uma métrica numérica, da proporção de sub-metas
 * concluídas ou de um percentual informado manualmente. O modo numérico mantém a fórmula:
 * <pre>(atual - inicial) / (meta - inicial) * 100, limitado a 0..100</pre>
 * Funciona para metas de aumento e redução; meta igual ao valor inicial é proibido.
 */
@Service
public class OkrService {
    static final Set<String> STATUS_OBJETIVO = Set.of("ATIVO", "CONCLUIDO", "ARQUIVADO");
    static final Set<String> MODOS_ACOMPANHAMENTO = Set.of("METRICA", "SUBMETAS", "MANUAL");
    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private final JdbcTemplate db;
    public OkrService(JdbcTemplate db) { this.db = db; }

    /** Mesma fórmula do {@link #progresso}, em SQL, para agregações. {@code r} é o alias de resultados_chave. */
    static String progressoSql(String r) {
        String metrica = "LEAST(100, GREATEST(0, ROUND((" + r + ".valor_atual - " + r + ".valor_inicial) * 100 / NULLIF(" + r + ".meta - " + r + ".valor_inicial, 0), 2)))";
        String checklist = "COALESCE((SELECT ROUND(COUNT(*) FILTER (WHERE sm.concluida)::numeric * 100 / NULLIF(COUNT(*), 0), 2) FROM submetas_resultados_chave sm WHERE sm.resultado_id = " + r + ".id AND sm.empresa_id = " + r + ".empresa_id), 0)";
        return "CASE WHEN " + r + ".modo_acompanhamento = 'MANUAL' THEN LEAST(100, GREATEST(0, " + r + ".progresso_manual)) "
                + "WHEN " + r + ".modo_acompanhamento = 'SUBMETAS' THEN " + checklist + " ELSE " + metrica + " END";
    }

    static BigDecimal progresso(BigDecimal inicial, BigDecimal meta, BigDecimal atual) {
        BigDecimal denominador = meta.subtract(inicial);
        if (denominador.signum() == 0) return BigDecimal.ZERO.setScale(2);
        BigDecimal p = atual.subtract(inicial).multiply(CEM).divide(denominador, 2, RoundingMode.HALF_UP);
        return p.max(BigDecimal.ZERO).min(CEM);
    }

    // ---------------- objetivos ----------------

    public List<Map<String, Object>> listarObjetivos(int empresa, String status, Integer squadId, Integer responsavelId, LocalDate de, LocalDate ate) {
        StringBuilder sql = new StringBuilder(SELECT_OBJETIVO + " WHERE o.empresa_id = ?");
        List<Object> p = new ArrayList<>(List.of(InventarioService.hoje(), empresa));
        if (status != null && !status.isBlank()) { sql.append(" AND o.status = ?"); p.add(Dados.opcao(status, STATUS_OBJETIVO, "status")); }
        else sql.append(" AND o.status <> 'ARQUIVADO'"); // arquivados só aparecem quando pedidos
        if (squadId != null) { sql.append(" AND o.squad_id = ?"); p.add(squadId); }
        if (responsavelId != null) { sql.append(" AND o.responsavel_id = ?"); p.add(responsavelId); }
        if (de != null) { sql.append(" AND o.periodo_fim >= ?"); p.add(de); }
        if (ate != null) { sql.append(" AND o.periodo_inicio <= ?"); p.add(ate); }
        sql.append(" ORDER BY o.periodo_fim, o.id");
        return db.queryForList(sql.toString(), p.toArray());
    }

    public Map<String, Object> buscarObjetivo(int empresa, int id) {
        List<Map<String, Object>> r = db.queryForList(SELECT_OBJETIVO + " WHERE o.empresa_id = ? AND o.id = ?", InventarioService.hoje(), empresa, id);
        if (r.isEmpty()) throw new NaoEncontradoException("Objetivo não encontrado.");
        Map<String, Object> o = r.get(0);
        List<Map<String, Object>> resultados = db.queryForList(SELECT_RESULTADO + " WHERE r.empresa_id = ? AND r.objetivo_id = ? ORDER BY r.prazo, r.id", empresa, id);
        for (Map<String, Object> resultado : resultados) {
            resultado.put("subMetas", db.queryForList(SELECT_SUBMETA + " WHERE sm.empresa_id = ? AND sm.resultado_id = ? ORDER BY sm.ordem, sm.id",
                    empresa, ((Number) resultado.get("id")).intValue()));
        }
        o.put("resultados", resultados);
        return o;
    }

    @Transactional
    public Map<String, Object> criarObjetivo(Autorizacao.Contexto c, Map<String, Object> d) {
        LocalDate inicio = Dados.data(d, "periodoInicio", true), fim = Dados.data(d, "periodoFim", true);
        if (fim.isBefore(inicio)) throw new IllegalArgumentException("O fim do período não pode ser anterior ao início.");
        Integer squad = Dados.inteiro(d, "squadId", false), resp = Dados.inteiro(d, "responsavelId", false);
        validarSquad(c.empresaId(), squad); validarResponsavel(c.empresaId(), resp);
        int id = db.queryForObject("""
                INSERT INTO objetivos(empresa_id, titulo, descricao, squad_id, responsavel_id, periodo_inicio, periodo_fim)
                VALUES (?,?,?,?,?,?,?) RETURNING id
                """, Integer.class, c.empresaId(), Dados.texto(d, "titulo", 150, true), Dados.texto(d, "descricao", 1000, false), squad, resp, inicio, fim);
        return buscarObjetivo(c.empresaId(), id);
    }

    @Transactional
    public Map<String, Object> atualizarObjetivo(int empresa, int id, Map<String, Object> d) {
        Map<String, Object> atual = buscarObjetivo(empresa, id);
        if ("ARQUIVADO".equals(atual.get("status"))) throw new IllegalArgumentException("Objetivo arquivado não pode ser alterado.");
        String titulo = d.containsKey("titulo") ? Dados.texto(d, "titulo", 150, true) : (String) atual.get("titulo");
        String descricao = d.containsKey("descricao") ? Dados.texto(d, "descricao", 1000, false) : (String) atual.get("descricao");
        LocalDate inicio = d.containsKey("periodoInicio") ? Dados.data(d, "periodoInicio", true) : ((java.sql.Date) atual.get("periodoInicio")).toLocalDate();
        LocalDate fim = d.containsKey("periodoFim") ? Dados.data(d, "periodoFim", true) : ((java.sql.Date) atual.get("periodoFim")).toLocalDate();
        if (fim.isBefore(inicio)) throw new IllegalArgumentException("O fim do período não pode ser anterior ao início.");
        Integer squad = d.containsKey("squadId") ? Dados.inteiro(d, "squadId", false) : (Integer) atual.get("squadId");
        Integer resp = d.containsKey("responsavelId") ? Dados.inteiro(d, "responsavelId", false) : (Integer) atual.get("responsavelId");
        validarSquad(empresa, squad); validarResponsavel(empresa, resp);
        db.update("UPDATE objetivos SET titulo=?, descricao=?, periodo_inicio=?, periodo_fim=?, squad_id=?, responsavel_id=?, updated_at=NOW() WHERE id=? AND empresa_id=?",
                titulo, descricao, inicio, fim, squad, resp, id, empresa);
        return buscarObjetivo(empresa, id);
    }

    /** "Excluir" arquivando: resultados e histórico permanecem consultáveis. */
    @Transactional
    public Map<String, Object> arquivarObjetivo(int empresa, int id) {
        buscarObjetivo(empresa, id);
        db.update("UPDATE objetivos SET status='ARQUIVADO', updated_at=NOW() WHERE id=? AND empresa_id=?", id, empresa);
        return buscarObjetivo(empresa, id);
    }

    // ---------------- resultados-chave ----------------

    @Transactional
    public Map<String, Object> criarResultado(Autorizacao.Contexto c, int objetivoId, Map<String, Object> d) {
        Map<String, Object> obj = buscarObjetivo(c.empresaId(), objetivoId);
        if ("ARQUIVADO".equals(obj.get("status"))) throw new IllegalArgumentException("Não é possível adicionar resultados a um objetivo arquivado.");
        String modo = modo(d.get("modoAcompanhamento"), "METRICA");
        BigDecimal inicial, meta, atual, percentual;
        if ("METRICA".equals(modo)) {
            inicial = Optional.ofNullable(Dados.decimal(d, "valorInicial", false)).orElse(BigDecimal.ZERO);
            meta = Dados.decimal(d, "meta", true);
            if (meta.compareTo(inicial) == 0) throw new IllegalArgumentException("A meta deve ser diferente do valor inicial.");
            atual = Optional.ofNullable(Dados.decimal(d, "valorAtual", false)).orElse(inicial);
            percentual = progresso(inicial, meta, atual);
        } else {
            inicial = BigDecimal.ZERO;
            meta = BigDecimal.valueOf(100);
            atual = BigDecimal.ZERO;
            percentual = "MANUAL".equals(modo) ? validarPercentual(Optional.ofNullable(Dados.decimal(d, "progressoManual", false)).orElse(BigDecimal.ZERO)) : BigDecimal.ZERO;
        }
        Integer resp = Dados.inteiro(d, "responsavelId", false);
        validarResponsavel(c.empresaId(), resp);
        int id = db.queryForObject("""
                INSERT INTO resultados_chave(empresa_id, objetivo_id, titulo, descricao, valor_inicial, meta, valor_atual, unidade, responsavel_id, prazo, status, modo_acompanhamento, progresso_manual)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Integer.class, c.empresaId(), objetivoId, Dados.texto(d, "titulo", 150, true), Dados.texto(d, "descricao", 1000, false),
                inicial, meta, atual, Dados.texto(d, "unidade", 30, false), resp, Dados.data(d, "prazo", true),
                percentual.compareTo(CEM) >= 0 ? "CONCLUIDO" : "EM_ANDAMENTO", modo, percentual);
        sincronizarObjetivo(c.empresaId(), objetivoId);
        return buscarResultado(c.empresaId(), id);
    }

    public Map<String, Object> buscarResultado(int empresa, int id) {
        List<Map<String, Object>> r = db.queryForList(SELECT_RESULTADO + " WHERE r.empresa_id = ? AND r.id = ?", empresa, id);
        if (r.isEmpty()) throw new NaoEncontradoException("Resultado-chave não encontrado.");
        return r.get(0);
    }

    @Transactional
    public Map<String, Object> atualizarResultado(int empresa, int id, Map<String, Object> d) {
        Map<String, Object> atual = buscarResultado(empresa, id);
        exigirEditavel(empresa, atual);
        String modoAtual = String.valueOf(atual.get("modoAcompanhamento"));
        String modo = d.containsKey("modoAcompanhamento") ? modo(d.get("modoAcompanhamento"), modoAtual) : modoAtual;
        BigDecimal inicial = (BigDecimal) atual.get("valorInicial");
        BigDecimal meta = (BigDecimal) atual.get("meta");
        BigDecimal valorAtual = (BigDecimal) atual.get("valorAtual");
        BigDecimal percentual = (BigDecimal) atual.get("progressoManual");
        if ("METRICA".equals(modo)) {
            inicial = d.containsKey("valorInicial") ? Dados.decimal(d, "valorInicial", true) : inicial;
            meta = d.containsKey("meta") ? Dados.decimal(d, "meta", true) : meta;
            valorAtual = d.containsKey("valorAtual") ? Dados.decimal(d, "valorAtual", true) : valorAtual;
            if (meta.compareTo(inicial) == 0) throw new IllegalArgumentException("A meta deve ser diferente do valor inicial.");
            percentual = progresso(inicial, meta, valorAtual);
        } else if ("MANUAL".equals(modo)) {
            BigDecimal atualPercentual = (BigDecimal) atual.get("progresso");
            percentual = validarPercentual(d.containsKey("progressoManual")
                    ? Dados.decimal(d, "progressoManual", true) : atualPercentual);
            inicial = BigDecimal.ZERO; meta = BigDecimal.valueOf(100); valorAtual = BigDecimal.ZERO;
        } else {
            if (!"SUBMETAS".equals(modo)) throw new IllegalArgumentException("Modo de acompanhamento inválido.");
            // As metas do checklist vêm das sub-metas, não dos valores numéricos.
            inicial = BigDecimal.ZERO; meta = BigDecimal.valueOf(100); valorAtual = BigDecimal.ZERO; percentual = BigDecimal.ZERO;
        }
        Integer resp = d.containsKey("responsavelId") ? Dados.inteiro(d, "responsavelId", false) : (Integer) atual.get("responsavelId");
        validarResponsavel(empresa, resp);
        LocalDate prazo = d.containsKey("prazo") ? Dados.data(d, "prazo", true) : ((java.sql.Date) atual.get("prazo")).toLocalDate();
        db.update("""
                UPDATE resultados_chave SET titulo=?, descricao=?, valor_inicial=?, meta=?, valor_atual=?, unidade=?, responsavel_id=?, prazo=?,
                       modo_acompanhamento=?, progresso_manual=?, updated_at=NOW() WHERE id=? AND empresa_id=?
                """, d.containsKey("titulo") ? Dados.texto(d, "titulo", 150, true) : atual.get("titulo"),
                d.containsKey("descricao") ? Dados.texto(d, "descricao", 1000, false) : atual.get("descricao"),
                inicial, meta, valorAtual, d.containsKey("unidade") ? Dados.texto(d, "unidade", 30, false) : atual.get("unidade"),
                resp, prazo, modo, percentual, id, empresa);
        reavaliarStatus(empresa, id);
        return buscarResultado(empresa, id);
    }

    /** Atualiza uma métrica numérica legada ou o percentual de um acompanhamento manual. */
    @Transactional
    public Map<String, Object> atualizarProgresso(Autorizacao.Contexto c, int id, Map<String, Object> d) {
        List<Map<String, Object>> travado = db.queryForList("SELECT valor_atual, progresso_manual, modo_acompanhamento, responsavel_id, objetivo_id, status FROM resultados_chave WHERE id=? AND empresa_id=? FOR UPDATE", id, c.empresaId());
        if (travado.isEmpty()) throw new NaoEncontradoException("Resultado-chave não encontrado.");
        Map<String, Object> linha = travado.get(0);
        Map<String, Object> atual = buscarResultado(c.empresaId(), id);
        exigirEditavel(c.empresaId(), atual);
        if (!podeAtuarNoResultado(c, linha))
            throw new AcessoNegadoException("Para atualizar este resultado, você precisa ser gestor de OKRs ou ter a permissão de atualização e estar definido como responsável.");
        String modo = String.valueOf(linha.get("modo_acompanhamento"));
        BigDecimal anterior, novo;
        if ("MANUAL".equals(modo)) {
            anterior = (BigDecimal) linha.get("progresso_manual");
            novo = validarPercentual(Dados.decimal(d, "percentual", true));
            if (novo.compareTo(anterior) == 0) throw new IllegalArgumentException("O percentual informado é igual ao atual.");
            db.update("UPDATE resultados_chave SET progresso_manual=?, updated_at=NOW() WHERE id=? AND empresa_id=?", novo, id, c.empresaId());
        } else if ("METRICA".equals(modo)) {
            anterior = (BigDecimal) linha.get("valor_atual");
            novo = Dados.decimal(d, "valorAtual", true);
            if (novo.compareTo(anterior) == 0) throw new IllegalArgumentException("O valor informado é igual ao valor atual.");
            db.update("UPDATE resultados_chave SET valor_atual=?, updated_at=NOW() WHERE id=? AND empresa_id=?", novo, id, c.empresaId());
        } else {
            throw new IllegalArgumentException("O progresso deste resultado é calculado pelas sub-metas. Marque ou desmarque as etapas para atualizá-lo.");
        }
        db.update("INSERT INTO historico_resultados_chave(empresa_id, resultado_id, valor_anterior, valor_novo, comentario, usuario_id) VALUES (?,?,?,?,?,?)",
                c.empresaId(), id, anterior, novo, Dados.texto(d, "comentario", 500, false), c.usuarioId());
        reavaliarStatus(c.empresaId(), id);
        return buscarResultado(c.empresaId(), id);
    }

    @Transactional
    public Map<String, Object> criarSubMeta(Autorizacao.Contexto c, int resultadoId, Map<String, Object> d) {
        Map<String, Object> resultado = buscarResultado(c.empresaId(), resultadoId);
        exigirEditavel(c.empresaId(), resultado);
        exigirModoSubMetas(resultado);
        Map<String, Object> responsavel = linhaResponsabilidade(c.empresaId(), resultadoId);
        if (!podeAtuarNoResultado(c, responsavel))
            throw new AcessoNegadoException("Para adicionar sub-metas, você precisa ser gestor de OKRs ou ter a permissão de atualização e estar definido como responsável.");
        int ordem = Optional.ofNullable(Dados.inteiro(d, "ordem", false)).orElse(0);
        int id = db.queryForObject("""
                INSERT INTO submetas_resultados_chave(empresa_id, resultado_id, titulo, descricao, ordem)
                VALUES (?,?,?,?,?) RETURNING id
                """, Integer.class, c.empresaId(), resultadoId, Dados.texto(d, "titulo", 150, true), Dados.texto(d, "descricao", 500, false), ordem);
        reavaliarStatus(c.empresaId(), resultadoId);
        return buscarSubMeta(c.empresaId(), id);
    }

    @Transactional
    public Map<String, Object> atualizarConclusaoSubMeta(Autorizacao.Contexto c, int subMetaId, Map<String, Object> d) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT sm.id, sm.resultado_id, sm.empresa_id, sm.concluida, r.responsavel_id, r.objetivo_id, r.status AS resultado_status
                  FROM submetas_resultados_chave sm JOIN resultados_chave r ON r.id=sm.resultado_id AND r.empresa_id=sm.empresa_id
                 WHERE sm.id=? AND sm.empresa_id=? FOR UPDATE OF sm, r
                """, subMetaId, c.empresaId());
        if (rows.isEmpty()) throw new NaoEncontradoException("Sub-meta não encontrada.");
        Map<String, Object> linha = rows.get(0);
        int resultadoId = ((Number) linha.get("resultado_id")).intValue();
        Map<String, Object> resultado = buscarResultado(c.empresaId(), resultadoId);
        exigirEditavel(c.empresaId(), resultado);
        exigirModoSubMetas(resultado);
        if (!podeAtuarNoResultado(c, linha))
            throw new AcessoNegadoException("Para marcar sub-metas, você precisa ser gestor de OKRs ou ter a permissão de atualização e estar definido como responsável.");
        Object valor = d.get("concluida");
        if (valor == null) throw new IllegalArgumentException("Informe se a sub-meta está concluída.");
        boolean concluida = valor instanceof Boolean b ? b : Boolean.parseBoolean(valor.toString());
        boolean anterior = Boolean.TRUE.equals(linha.get("concluida"));
        if (anterior == concluida) return buscarSubMeta(c.empresaId(), subMetaId);
        db.update("""
                UPDATE submetas_resultados_chave SET concluida=?, concluida_em=CASE WHEN ? THEN NOW() ELSE NULL END,
                       concluida_por=CASE WHEN ? THEN ? ELSE NULL END, updated_at=NOW()
                 WHERE id=? AND empresa_id=?
                """, concluida, concluida, concluida, c.usuarioId(), subMetaId, c.empresaId());
        reavaliarStatus(c.empresaId(), resultadoId);
        return buscarSubMeta(c.empresaId(), subMetaId);
    }

    @Transactional
    public Map<String, Object> atualizarSubMeta(Autorizacao.Contexto c, int subMetaId, Map<String, Object> d) {
        Map<String, Object> sm = buscarSubMeta(c.empresaId(), subMetaId);
        int resultadoId = ((Number) sm.get("resultadoId")).intValue();
        Map<String, Object> resultado = buscarResultado(c.empresaId(), resultadoId);
        exigirEditavel(c.empresaId(), resultado);
        exigirModoSubMetas(resultado);
        String titulo = d.containsKey("titulo") ? Dados.texto(d, "titulo", 150, true) : (String) sm.get("titulo");
        String descricao = d.containsKey("descricao") ? Dados.texto(d, "descricao", 500, false) : (String) sm.get("descricao");
        db.update("UPDATE submetas_resultados_chave SET titulo=?, descricao=?, updated_at=NOW() WHERE id=? AND empresa_id=?", titulo, descricao, subMetaId, c.empresaId());
        return buscarSubMeta(c.empresaId(), subMetaId);
    }

    @Transactional
    public Map<String, Object> removerSubMeta(Autorizacao.Contexto c, int subMetaId) {
        Map<String, Object> sm = buscarSubMeta(c.empresaId(), subMetaId);
        int resultadoId = ((Number) sm.get("resultadoId")).intValue();
        Map<String, Object> resultado = buscarResultado(c.empresaId(), resultadoId);
        exigirEditavel(c.empresaId(), resultado);
        exigirModoSubMetas(resultado);
        db.update("DELETE FROM submetas_resultados_chave WHERE id=? AND empresa_id=?", subMetaId, c.empresaId());
        reavaliarStatus(c.empresaId(), resultadoId);
        return buscarResultado(c.empresaId(), resultadoId);
    }

    public Map<String, Object> buscarSubMeta(int empresa, int id) {
        List<Map<String, Object>> rows = db.queryForList(SELECT_SUBMETA + " WHERE sm.empresa_id=? AND sm.id=?", empresa, id);
        if (rows.isEmpty()) throw new NaoEncontradoException("Sub-meta não encontrada.");
        return rows.get(0);
    }

    private Map<String, Object> linhaResponsabilidade(int empresa, int resultadoId) {
        return db.queryForMap("SELECT responsavel_id, objetivo_id FROM resultados_chave WHERE id=? AND empresa_id=?", resultadoId, empresa);
    }

    private static void exigirModoSubMetas(Map<String, Object> resultado) {
        if (!"SUBMETAS".equals(resultado.get("modoAcompanhamento")))
            throw new IllegalArgumentException("Este resultado não está configurado para acompanhamento por sub-metas.");
    }

    private static String modo(Object valor, String padrao) {
        String informado = valor == null || valor.toString().isBlank() ? padrao : valor.toString();
        return Dados.opcao(informado, MODOS_ACOMPANHAMENTO, "modoAcompanhamento");
    }

    private static BigDecimal validarPercentual(BigDecimal valor) {
        if (valor == null || valor.compareTo(BigDecimal.ZERO) < 0 || valor.compareTo(CEM) > 0)
            throw new IllegalArgumentException("O percentual deve estar entre 0 e 100.");
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    /** "Excluir" cancelando: o resultado e seu histórico continuam disponíveis para consulta. */
    @Transactional
    public Map<String, Object> cancelarResultado(int empresa, int id) {
        Map<String, Object> atual = buscarResultado(empresa, id);
        db.update("UPDATE resultados_chave SET status='CANCELADO', updated_at=NOW() WHERE id=? AND empresa_id=?", id, empresa);
        sincronizarObjetivo(empresa, ((Number) atual.get("objetivoId")).intValue());
        return buscarResultado(empresa, id);
    }

    public List<Map<String, Object>> historico(int empresa, int resultadoId) {
        buscarResultado(empresa, resultadoId);
        return db.queryForList("""
                SELECT h.id, h.valor_anterior AS "valorAnterior", h.valor_novo AS "valorNovo", h.comentario,
                       h.usuario_id AS "usuarioId", u.nome AS "usuarioNome", h.created_at AS "criadoEm"
                  FROM historico_resultados_chave h LEFT JOIN usuarios u ON u.id = h.usuario_id AND u.empresa_id = h.empresa_id
                 WHERE h.resultado_id = ? AND h.empresa_id = ? ORDER BY h.id DESC
                """, resultadoId, empresa);
    }

    // ---------------- indicadores ----------------

    /** Progresso geral, por Squad e por responsável. Também alimenta o dashboard estratégico. */
    public Map<String, Object> resumo(int empresa) {
        String prog = progressoSql("r");
        Map<String, Object> geral = db.queryForMap("""
                SELECT (SELECT COUNT(*) FROM objetivos WHERE empresa_id = ? AND status = 'ATIVO') AS "objetivosAtivos",
                       (SELECT COUNT(*) FROM objetivos WHERE empresa_id = ? AND status = 'CONCLUIDO') AS "objetivosConcluidos",
                       (SELECT COUNT(*) FROM objetivos WHERE empresa_id = ? AND status = 'ATIVO' AND periodo_fim < ?) AS "objetivosAtrasados",
                       (SELECT COUNT(*) FROM resultados_chave r JOIN objetivos o ON o.id = r.objetivo_id AND o.empresa_id = r.empresa_id
                         WHERE r.empresa_id = ? AND r.status = 'EM_ANDAMENTO' AND o.status <> 'ARQUIVADO') AS "resultadosEmAndamento",
                       (SELECT COALESCE(ROUND(AVG(""" + prog + """
                ), 2), 0) FROM resultados_chave r JOIN objetivos o ON o.id = r.objetivo_id AND o.empresa_id = r.empresa_id
                         WHERE r.empresa_id = ? AND r.status <> 'CANCELADO' AND o.status <> 'ARQUIVADO') AS "progressoMedio"
                """, empresa, empresa, empresa, InventarioService.hoje(), empresa, empresa);
        List<Map<String, Object>> porSquad = db.queryForList("""
                SELECT s.id, s.nome, COUNT(DISTINCT o.id) AS objetivos, COALESCE(ROUND(AVG(""" + prog + """
                ), 2), 0) AS progresso
                  FROM squads s
                  LEFT JOIN objetivos o ON o.squad_id = s.id AND o.empresa_id = s.empresa_id AND o.status <> 'ARQUIVADO'
                  LEFT JOIN resultados_chave r ON r.objetivo_id = o.id AND r.empresa_id = o.empresa_id AND r.status <> 'CANCELADO'
                 WHERE s.empresa_id = ? GROUP BY s.id, s.nome ORDER BY LOWER(s.nome)
                """, empresa);
        List<Map<String, Object>> porResponsavel = db.queryForList("""
                SELECT u.id, u.nome, COUNT(r.id) AS resultados, COALESCE(ROUND(AVG(""" + prog + """
                ), 2), 0) AS progresso
                  FROM resultados_chave r
                  JOIN objetivos o ON o.id = r.objetivo_id AND o.empresa_id = r.empresa_id AND o.status <> 'ARQUIVADO'
                  JOIN usuarios u ON u.id = r.responsavel_id AND u.empresa_id = r.empresa_id
                 WHERE r.empresa_id = ? AND r.status <> 'CANCELADO' GROUP BY u.id, u.nome ORDER BY LOWER(u.nome)
                """, empresa);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("geral", geral); r.put("porSquad", porSquad); r.put("porResponsavel", porResponsavel);
        return r;
    }

    // ---------------- internos ----------------

    private static final String SELECT_OBJETIVO = """
            SELECT o.id, o.titulo, o.descricao, o.squad_id AS "squadId", s.nome AS "squadNome",
                   o.responsavel_id AS "responsavelId", u.nome AS "responsavelNome",
                   o.periodo_inicio AS "periodoInicio", o.periodo_fim AS "periodoFim", o.status, o.created_at AS "criadoEm",
                   COALESCE((SELECT ROUND(AVG(""" + progressoSql("r") + """
            ), 2) FROM resultados_chave r WHERE r.objetivo_id = o.id AND r.empresa_id = o.empresa_id AND r.status <> 'CANCELADO'), 0) AS progresso,
                   (SELECT COUNT(*) FROM resultados_chave r WHERE r.objetivo_id = o.id AND r.empresa_id = o.empresa_id AND r.status <> 'CANCELADO') AS "totalResultados",
                   (SELECT COUNT(*) FROM submetas_resultados_chave sm JOIN resultados_chave r ON r.id=sm.resultado_id AND r.empresa_id=sm.empresa_id
                     WHERE r.objetivo_id=o.id AND r.empresa_id=o.empresa_id AND r.status <> 'CANCELADO') AS "totalSubMetas",
                   (o.status = 'ATIVO' AND o.periodo_fim < ?) AS atrasado
              FROM objetivos o
              LEFT JOIN squads s ON s.id = o.squad_id AND s.empresa_id = o.empresa_id
              LEFT JOIN usuarios u ON u.id = o.responsavel_id AND u.empresa_id = o.empresa_id
            """;

    private static final String SELECT_RESULTADO = """
            SELECT r.id, r.objetivo_id AS "objetivoId", r.titulo, r.descricao, r.valor_inicial AS "valorInicial", r.meta,
                   r.valor_atual AS "valorAtual", r.unidade, r.responsavel_id AS "responsavelId", u.nome AS "responsavelNome",
                   r.prazo, r.status, r.modo_acompanhamento AS "modoAcompanhamento", r.progresso_manual AS "progressoManual",
                   (SELECT COUNT(*) FROM submetas_resultados_chave sm WHERE sm.resultado_id=r.id AND sm.empresa_id=r.empresa_id) AS "totalSubMetas",
                   (SELECT COUNT(*) FROM submetas_resultados_chave sm WHERE sm.resultado_id=r.id AND sm.empresa_id=r.empresa_id AND sm.concluida) AS "subMetasConcluidas",
                   """ + progressoSql("r") + " AS progresso\n" + """
              FROM resultados_chave r LEFT JOIN usuarios u ON u.id = r.responsavel_id AND u.empresa_id = r.empresa_id
            """;

    private static final String SELECT_SUBMETA = """
            SELECT sm.id, sm.resultado_id AS "resultadoId", sm.titulo, sm.descricao, sm.concluida, sm.ordem,
                   sm.concluida_em AS "concluidaEm", sm.concluida_por AS "concluidaPor",
                   u.nome AS "concluidaPorNome", sm.created_at AS "criadaEm"
              FROM submetas_resultados_chave sm
              LEFT JOIN usuarios u ON u.id=sm.concluida_por AND u.empresa_id=sm.empresa_id
            """;

    /** Gestores podem atuar em qualquer resultado; demais usuários precisam da permissão okrs.update e ser responsáveis. */
    private boolean podeAtuarNoResultado(Autorizacao.Contexto c, Map<String, Object> linha) {
        return c.proprietario() || c.pode("okrs.manage") || (c.pode("okrs.update") && isResponsavel(c, linha));
    }

    private boolean isResponsavel(Autorizacao.Contexto c, Map<String, Object> linha) {
        Object resp = linha.get("responsavel_id");
        if (resp != null && ((Number) resp).intValue() == c.usuarioId()) return true;
        Integer objResp = db.queryForList("SELECT responsavel_id FROM objetivos WHERE id=? AND empresa_id=?", Integer.class, linha.get("objetivo_id"), c.empresaId()).stream().findFirst().orElse(null);
        return objResp != null && objResp == c.usuarioId();
    }

    private void exigirEditavel(int empresa, Map<String, Object> resultado) {
        if ("CANCELADO".equals(resultado.get("status"))) throw new IllegalArgumentException("Resultado-chave cancelado não pode ser alterado.");
        String statusObjetivo = db.queryForObject("SELECT status FROM objetivos WHERE id=? AND empresa_id=?", String.class, resultado.get("objetivoId"), empresa);
        if ("ARQUIVADO".equals(statusObjetivo)) throw new IllegalArgumentException("O objetivo está arquivado.");
    }

    /** Resultado concluído quando o progresso chega a 100%; volta a andamento se a meta ou o valor mudarem. */
    private void reavaliarStatus(int empresa, int resultadoId) {
        Map<String, Object> r = buscarResultado(empresa, resultadoId);
        if ("CANCELADO".equals(r.get("status"))) return;
        boolean concluido = ((BigDecimal) r.get("progresso")).compareTo(CEM) >= 0;
        db.update("UPDATE resultados_chave SET status=? WHERE id=? AND empresa_id=?", concluido ? "CONCLUIDO" : "EM_ANDAMENTO", resultadoId, empresa);
        sincronizarObjetivo(empresa, ((Number) r.get("objetivoId")).intValue());
    }

    /** Objetivo conclui quando todos os resultados não cancelados estão concluídos; reabre se algum deixar de estar. */
    private void sincronizarObjetivo(int empresa, int objetivoId) {
        Map<String, Object> cont = db.queryForMap("""
                SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE status = 'CONCLUIDO') AS concluidos
                  FROM resultados_chave WHERE objetivo_id = ? AND empresa_id = ? AND status <> 'CANCELADO'
                """, objetivoId, empresa);
        long total = ((Number) cont.get("total")).longValue(), concluidos = ((Number) cont.get("concluidos")).longValue();
        if (total > 0 && total == concluidos)
            db.update("UPDATE objetivos SET status='CONCLUIDO', updated_at=NOW() WHERE id=? AND empresa_id=? AND status='ATIVO'", objetivoId, empresa);
        else
            db.update("UPDATE objetivos SET status='ATIVO', updated_at=NOW() WHERE id=? AND empresa_id=? AND status='CONCLUIDO'", objetivoId, empresa);
    }

    private void validarSquad(int empresa, Integer squad) {
        if (squad != null && db.queryForObject("SELECT COUNT(*) FROM squads WHERE id=? AND empresa_id=?", Integer.class, squad, empresa) == 0)
            throw new IllegalArgumentException("Squad inválida.");
    }

    private void validarResponsavel(int empresa, Integer usuario) {
        if (usuario != null && db.queryForObject("SELECT COUNT(*) FROM usuarios WHERE id=? AND empresa_id=? AND ativo", Integer.class, usuario, empresa) == 0)
            throw new IllegalArgumentException("Responsável inválido ou inativo.");
    }
}
