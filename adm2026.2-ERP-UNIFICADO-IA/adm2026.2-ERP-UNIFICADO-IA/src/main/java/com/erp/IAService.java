package com.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Núcleo seguro do assistente de ERP.
 *
 * O modelo somente interpreta intenção e, opcionalmente, escreve uma análise.
 * O Java controla escopo, empresa, consultas, cálculos e dados que podem ser expostos.
 */
@Service
public class IAService {

    private static final int MAX_CONSULTAS = 8;
    private static final int MAX_LISTA = 200;
    private static final int MAX_EXIBICAO_COMPARACAO = 80;
    private static final int MAX_HISTORICO = 8;
    private static final int MAX_PERGUNTA = 1200;
    private static final long MAX_DIAS_CONSULTA = 366;
    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId FUSO_ERP = ZoneId.of("America/Recife");
    private static final Pattern JSON_CODE_FENCE = Pattern.compile("^```(?:json)?\\s*|\\s*```$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Month> MESES = Map.ofEntries(
            Map.entry("janeiro", Month.JANUARY),
            Map.entry("fevereiro", Month.FEBRUARY),
            Map.entry("marco", Month.MARCH),
            Map.entry("abril", Month.APRIL),
            Map.entry("maio", Month.MAY),
            Map.entry("junho", Month.JUNE),
            Map.entry("julho", Month.JULY),
            Map.entry("agosto", Month.AUGUST),
            Map.entry("setembro", Month.SEPTEMBER),
            Map.entry("outubro", Month.OCTOBER),
            Map.entry("novembro", Month.NOVEMBER),
            Map.entry("dezembro", Month.DECEMBER)
    );

    private final JdbcTemplate db;
    private final OllamaClient ollama;
    private final ObjectMapper mapper;

    public IAService(JdbcTemplate db, OllamaClient ollama, ObjectMapper mapper) {
        this.db = db;
        this.ollama = ollama;
        this.mapper = mapper;
    }

    public String responder(int empresaId, IAPerguntaRequest request) {
        String pergunta = request == null || request.pergunta() == null
                ? ""
                : request.pergunta().trim();

        if (pergunta.isBlank()) return "Digite uma pergunta para o assistente.";
        if (pergunta.length() > MAX_PERGUNTA) {
            return "Sua pergunta é muito longa. Faça uma pergunta mais objetiva sobre os dados do ERP.";
        }

        String bloqueio = bloquearAcaoOuDadoSensivel(pergunta);
        if (bloqueio != null) return bloqueio;

        List<IAHistoricoItem> historico = limitarHistorico(request.historico());

        // Perguntas factuais simples e de alto risco de alucinação são resolvidas
        // deterministicamente pelo backend antes de chamar o modelo para planejar.
        String respostaDeterministica = responderIntencaoDeterministica(empresaId, pergunta);
        if (respostaDeterministica != null) return respostaDeterministica;

        if (!pareceSerPerguntaDoERP(pergunta, historico)) return respostaForaEscopo();

        IAPlano plano = planejar(pergunta, historico);
        if (plano == null || !plano.permitido() || plano.consultas().isEmpty()) {
            return respostaForaEscopo();
        }

        Map<String, Object> contexto;
        try {
            contexto = coletarContexto(empresaId, plano);
        } catch (Exception e) {
            System.err.println("Erro ao consultar dados para a IA: " + e.getMessage());
            return "Não consegui consultar os dados do ERP agora. Verifique a conexão com o banco e tente novamente.";
        }

        String respostaFactual = respostaFactual(plano, contexto);
        if (respostaFactual == null || respostaFactual.isBlank()) {
            respostaFactual = respostaFallback(plano, contexto);
        }

        if (pedeAnalise(pergunta)) {
            String analise = gerarAnaliseSemRecalculo(pergunta, historico, contexto);
            if (analise != null && !analise.isBlank() && analiseSegura(analise)) {
                return respostaFactual + "\n\n" + limparResposta(analise);
            }
        }

        return respostaFactual;
    }

    private String responderIntencaoDeterministica(int empresaId, String pergunta) {
        String p = normalizar(pergunta);

        if (ePerguntaPrecoProduto(p)) {
            String termo = extrairTermoProdutoPreco(p);
            if (termo.isBlank()) {
                return "Qual produto você quer consultar? Informe o nome do produto para eu buscar o preço cadastrado no ERP.";
            }

            List<Map<String, Object>> encontrados = produtoPreco(empresaId, List.of(termo));
            return formatarPrecos(encontrados);
        }

        return null;
    }

    private boolean ePerguntaPrecoProduto(String p) {
        if (p == null || p.isBlank()) return false;
        boolean preco = p.contains("quanto custa") || p.contains("quanto custava")
                || p.contains("preco de") || p.contains("preco do") || p.contains("preco da")
                || p.contains("valor do produto");
        if (!preco) return false;

        // Se o usuário está perguntando explicitamente sobre venda/faturamento,
        // não tratar como consulta de preço cadastrado.
        return !(p.contains("quanto vendi") || p.contains("quanto foi vendido")
                || p.contains("faturamento") || p.contains("receita"));
    }

    private String extrairTermoProdutoPreco(String p) {
        String termo = p;
        termo = termo.replaceFirst("^.*?quanto\\s+(?:custa|custava)\\s+", "");
        termo = termo.replaceFirst("^(?:o|a|um|uma)\\s+", "");
        termo = termo.replaceFirst("^.*?preco\\s+(?:de|do|da)\\s+", "");
        termo = termo.replaceFirst("^.*?valor\\s+do\\s+produto\\s+", "");
        termo = termo.replaceAll("[?!.,;:]+$", "").trim();

        for (String prefixo : List.of("produto ", "item ", "mercadoria ")) {
            if (termo.startsWith(prefixo)) termo = termo.substring(prefixo.length()).trim();
        }
        return termo;
    }

    /**
     * O Gemma planeja; o backend não confia cegamente nele.
     * Se o JSON vier incompleto/ruim, usa fallback local.
     */
    private IAPlano planejar(String pergunta, List<IAHistoricoItem> historico) {
        LocalDate hoje = hoje();

        String prompt = """
                Você é o PLANEJADOR de um assistente de ERP brasileiro.

                Sua função NÃO é responder ao usuário.
                Sua função é transformar a pergunta em um plano de CONSULTAS DE LEITURA.

                DATA DE HOJE: %s

                O backend já aplicará regras de segurança. Ainda assim:
                - Somente consultas de leitura.
                - Nunca crie, edite, exclua, cancele ou altere dados.
                - Nunca peça senha, token, credencial, CPF, CNPJ, documento,
                  telefone, celular, e-mail, endereço ou outros dados pessoais.
                - Nunca peça acesso ao banco diretamente.
                - Só use dados da empresa autenticada.
                - Assuntos fora do ERP devem ser marcados como permitido=false.

                FERRAMENTAS DISPONÍVEIS:
                VENDAS_RESUMO = quantidade e total das vendas; o total vem de vendas.total.
                VENDAS_LISTA = cada venda do período com id, data, status, cliente,
                               total e itens/produtos da venda.
                VENDAS_PRODUTOS = produtos vendidos no período, por produto_id,
                                  nome, quantidade e valor dos itens vendidos.
                PRODUTOS_MAIS_VENDIDOS = ranking por quantidade vendida, maior primeiro.
                PRODUTOS_MENOS_VENDIDOS = ranking por quantidade vendida, menor primeiro.
                PRODUTOS_SEM_VENDAS = produtos ativos sem venda no período.
                COMPRAS_RESUMO = quantidade e total das compras no período.
                COMPRAS_LISTA = cada compra do período com id, data, status, fornecedor e total.
                COMPRAS_PRODUTOS = produtos comprados no período, por produto_id, quantidade e valor.
                ESTOQUE_BAIXO = produtos no mínimo ou abaixo do mínimo.
                ESTOQUE_LISTA = produtos e estoque atual.
                PRODUTOS = catálogo ativo, preço, categoria e estoque.
                CLIENTES_RESUMO = quantidade de clientes compradores e total vendido.
                CLIENTES_RANKING = clientes por total comprado, usando somente nome e valores agregados.
                FORNECEDORES_RESUMO = fornecedores utilizados e total comprado.

                REGRAS DE PLANEJAMENTO:
                1. "liste todas as vendas" -> VENDAS_LISTA.
                2. "quais produtos foram vendidos" -> VENDAS_PRODUTOS.
                3. "produto mais vendido" -> PRODUTOS_MAIS_VENDIDOS.
                4. "produto menos vendido", "menor saída", "vendendo menos" -> PRODUTOS_MENOS_VENDIDOS.
                5. "produto parado", "sem venda" -> PRODUTOS_SEM_VENDAS.
                6. Comparação entre dois meses/períodos: crie uma consulta para CADA período.
                7. Produto em dois meses: crie uma VENDAS_PRODUTOS para cada mês e informe o nome do produto em produtos[].
                8. Dois produtos no mesmo período: uma VENDAS_PRODUTOS pode conter os dois nomes em produtos[].
                9. "quanto vendi", "faturamento" ou "receita" -> VENDAS_RESUMO.
                10. "quanto custa", "quanto custava", "preço de" ou "valor do produto" -> PRODUTO_PRECO.
                    Se houver um nome do produto, informe esse nome em produtos[].
                11. NUNCA use VENDAS_LISTA para uma pergunta que pede apenas o preço de um produto.
                12. NUNCA use estoque para responder o preço cadastrado de um produto.
                13. Não invente datas. Quando o usuário disser um mês, informe o início e o fim daquele mês.
                12. Datas devem ser YYYY-MM-DD.
                13. Máximo de %d consultas.

                RETORNE SOMENTE JSON VÁLIDO:
                {
                  "permitido": true,
                  "motivo": "",
                  "consultas": [
                    {
                      "ferramenta": "VENDAS_PRODUTOS",
                      "inicio": "2026-08-01",
                      "fim": "2026-08-31",
                      "produtos": ["Produto A"]
                    }
                  ],
                  "calculos": ["DIFERENCA_QUANTIDADE","DIFERENCA_VALOR","VARIACAO_PERCENTUAL"]
                }

                PERGUNTA ATUAL:
                %s

                HISTÓRICO RECENTE:
                %s
                """.formatted(hoje, MAX_CONSULTAS, pergunta, formatarHistorico(historico));

        String json = ollama.gerarJson(prompt);
        IAPlano interpretado = interpretarPlano(json);
        if (interpretado != null && interpretado.permitido() && !interpretado.consultas().isEmpty()) {
            IAPlano validado = validarPlano(interpretado, pergunta);
            if (validado.permitido() && !validado.consultas().isEmpty()) return validado;
        }

        return planoFallback(pergunta);
    }

    private IAPlano interpretarPlano(String json) {
        if (json == null || json.isBlank()) return null;

        try {
            JsonNode root = mapper.readTree(limparJson(json));
            boolean permitido = root.path("permitido").asBoolean(false);
            String motivo = root.path("motivo").asText("");

            List<String> produtosGlobais = lerStrings(root.get("produtos"), 10, 150);
            List<IAConsulta> consultas = new ArrayList<>();
            JsonNode arr = root.get("consultas");

            if (arr != null && arr.isArray()) {
                for (JsonNode item : arr) {
                    if (consultas.size() >= MAX_CONSULTAS) break;

                    String nomeFerramenta = item.path("ferramenta").asText("").trim().toUpperCase(Locale.ROOT);
                    IATool ferramenta;
                    try {
                        ferramenta = IATool.valueOf(nomeFerramenta);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }

                    LocalDate inicio = dataOuNula(item.path("inicio").asText(null));
                    LocalDate fim = dataOuNula(item.path("fim").asText(null));
                    List<String> produtos = lerStrings(item.get("produtos"), 10, 150);
                    if (produtos.isEmpty()) produtos = produtosGlobais;

                    consultas.add(new IAConsulta(ferramenta, inicio, fim, produtos));
                }
            }

            List<String> calculos = new ArrayList<>();
            JsonNode calc = root.get("calculos");
            if (calc != null && calc.isArray()) {
                for (JsonNode item : calc) {
                    String valor = item.asText("").trim().toUpperCase(Locale.ROOT);
                    if (Set.of(
                            "DIFERENCA_VALOR",
                            "DIFERENCA_QUANTIDADE",
                            "VARIACAO_PERCENTUAL",
                            "TOTAL",
                            "MAIOR",
                            "MENOR"
                    ).contains(valor)) {
                        calculos.add(valor);
                    }
                }
            }

            return new IAPlano(
                    permitido,
                    motivo,
                    consultas,
                    calculos.stream().distinct().limit(8).toList()
            );
        } catch (Exception e) {
            return null;
        }
    }

    private IAPlano validarPlano(IAPlano plano, String pergunta) {
        if (plano == null || !plano.permitido()) return new IAPlano(false, "plano_negado", List.of(), List.of());

        List<IAConsulta> consultas = new ArrayList<>();
        for (IAConsulta consulta : plano.consultas()) {
            if (consulta == null || consulta.ferramenta() == null) continue;

            Intervalo intervalo = normalizarIntervalo(consulta.inicio(), consulta.fim());
            if (intervalo == null) continue;
            if (ChronoUnit.DAYS.between(intervalo.inicio(), intervalo.fim()) > MAX_DIAS_CONSULTA) continue;

            List<String> produtos = consulta.produtos() == null
                    ? List.of()
                    : consulta.produtos().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .limit(10)
                    .toList();

            consultas.add(new IAConsulta(
                    consulta.ferramenta(),
                    intervalo.inicio(),
                    intervalo.fim(),
                    produtos
            ));

            if (consultas.size() >= MAX_CONSULTAS) break;
        }

        if (consultas.isEmpty()) return planoFallback(pergunta);
        return new IAPlano(true, "", consultas, plano.calculos());
    }

    /** Fallback para quando o Gemma não entrega um plano válido. */
    private IAPlano planoFallback(String pergunta) {
        String p = normalizar(pergunta);
        if (p.isBlank()) return new IAPlano(false, "vazio", List.of(), List.of());
        if (contemAcaoEscrita(p)) return new IAPlano(false, "acao_de_escrita", List.of(), List.of());
        if (contemDadoSensivel(p)) return new IAPlano(false, "dado_sensivel", List.of(), List.of());

        List<Periodo> periodos = extrairPeriodos(p);
        if (periodos.isEmpty()) periodos = List.of(periodoMesAtual());

        IATool ferramenta = ferramentaFallback(p);
        if (ferramenta == null) return new IAPlano(false, "fora_do_escopo", List.of(), List.of());

        List<String> produtos = extrairNomesProdutosObvios(perguntasSemMeses(p));
        List<IAConsulta> consultas = periodos.stream()
                .limit(MAX_CONSULTAS)
                .map(periodo -> new IAConsulta(ferramenta, periodo.inicio(), periodo.fim(), produtos))
                .toList();

        List<String> calculos = p.contains("compar") || p.contains("diferenca") || p.contains("aumentou") || p.contains("diminuiu")
                ? List.of("DIFERENCA_VALOR", "DIFERENCA_QUANTIDADE", "VARIACAO_PERCENTUAL")
                : List.of();

        return new IAPlano(true, "fallback", consultas, calculos);
    }

    private IATool ferramentaFallback(String p) {
        boolean lista = p.contains("lista") || p.contains("listar") || p.contains("todas as") || p.contains("todos os") || p.contains("mostre");
        boolean venda = contemPalavra(p, "venda") || contemPalavra(p, "vendas") || contemPalavra(p, "vendi");
        boolean compra = contemPalavra(p, "compra") || contemPalavra(p, "compras") || contemPalavra(p, "comprei");
        boolean produto = contemPalavra(p, "produto") || contemPalavra(p, "produtos");

        if (venda && lista && (p.contains("venda") || p.contains("vendas"))) return IATool.VENDAS_LISTA;
        if (venda && produto && (p.contains("mais vendido") || p.contains("mais vendidos") || p.contains("vende mais"))) return IATool.PRODUTOS_MAIS_VENDIDOS;
        if (venda && produto && (p.contains("menos vendido") || p.contains("menos vendidos") || p.contains("vende menos") || p.contains("menor saida") || p.contains("vendendo menos"))) return IATool.PRODUTOS_MENOS_VENDIDOS;
        if (produto && (p.contains("parado") || p.contains("sem venda") || p.contains("sem vendas") || p.contains("nao vendeu"))) return IATool.PRODUTOS_SEM_VENDAS;
        if (venda && produto) return IATool.VENDAS_PRODUTOS;
        if (compra && lista) return IATool.COMPRAS_LISTA;
        if (compra && produto) return IATool.COMPRAS_PRODUTOS;
        if (compra) return IATool.COMPRAS_RESUMO;
        if (p.contains("estoque") && (p.contains("baixo") || p.contains("minimo") || p.contains("repor"))) return IATool.ESTOQUE_BAIXO;
        if (p.contains("estoque")) return IATool.ESTOQUE_LISTA;
        if (p.contains("cliente") && (p.contains("maior") || p.contains("mais") || p.contains("top"))) return IATool.CLIENTES_RANKING;
        if (p.contains("cliente")) return IATool.CLIENTES_RESUMO;
        if (p.contains("fornecedor")) return IATool.FORNECEDORES_RESUMO;
        if (p.contains("quanto custa") || p.contains("quanto custava") || p.contains("preco de")
                || p.contains("preco do") || p.contains("preco da") || p.contains("valor do produto")
                || p.contains("valor da mercadoria")) return IATool.PRODUTO_PRECO;
        if (p.contains("faturamento") || p.contains("faturou") || p.contains("receita") || venda) return IATool.VENDAS_RESUMO;
        return null;
    }

    private Map<String, Object> coletarContexto(int empresaId, IAPlano plano) {
        Map<String, Object> contexto = new LinkedHashMap<>();
        contexto.put("data_consulta", hoje().toString());
        contexto.put("fonte_numerica", "Dados calculados pelo backend a partir das tabelas autorizadas do ERP.");
        contexto.put("regra_financeira", "Faturamento e total vendido usam exclusivamente vendas.total. Estoque nunca representa faturamento.");

        int indice = 1;
        List<Map<String, Object>> resultados = new ArrayList<>();

        for (IAConsulta consulta : plano.consultas()) {
            Object dados = executarFerramenta(empresaId, consulta);

            Map<String, Object> pacote = new LinkedHashMap<>();
            pacote.put("ferramenta", consulta.ferramenta().name());
            pacote.put("inicio", consulta.inicio().toString());
            pacote.put("fim", consulta.fim().toString());
            if (!consulta.produtos().isEmpty()) pacote.put("produtos_solicitados", consulta.produtos());
            pacote.put("dados", dados);

            contexto.put("consulta_" + indice, pacote);
            resultados.add(Map.of(
                    "ferramenta", consulta.ferramenta().name(),
                    "inicio", consulta.inicio().toString(),
                    "fim", consulta.fim().toString(),
                    "dados", dados
            ));
            indice++;
        }

        contexto.put("comparacoes_calculadas", construirComparacoes(resultados));
        return contexto;
    }

    private Object executarFerramenta(int empresaId, IAConsulta consulta) {
        String filtro = filtroData(consulta);
        return switch (consulta.ferramenta()) {
            case VENDAS_RESUMO -> resumoVendas(empresaId, filtro);
            case VENDAS_LISTA -> vendasLista(empresaId, filtro);
            case VENDAS_PRODUTOS -> vendasProdutos(empresaId, consulta, filtro, "DESC");
            case PRODUTOS_MAIS_VENDIDOS -> vendasProdutos(empresaId, consulta, filtro, "DESC");
            case PRODUTOS_MENOS_VENDIDOS -> vendasProdutos(empresaId, consulta, filtro, "ASC");
            case PRODUTOS_SEM_VENDAS -> produtosSemVendas(empresaId, filtro);
            case PRODUTO_PRECO -> produtoPreco(empresaId, consulta.produtos());
            case COMPRAS_RESUMO -> resumoCompras(empresaId, filtro);
            case COMPRAS_LISTA -> comprasLista(empresaId, filtro);
            case COMPRAS_PRODUTOS -> comprasProdutos(empresaId, filtro);
            case ESTOQUE_BAIXO -> estoqueBaixo(empresaId);
            case ESTOQUE_LISTA -> estoqueLista(empresaId);
            case PRODUTOS -> produtos(empresaId);
            case CLIENTES_RESUMO -> clientesResumo(empresaId, filtro);
            case CLIENTES_RANKING -> clientesRanking(empresaId, filtro);
            case FORNECEDORES_RESUMO -> fornecedoresResumo(empresaId, filtro);
        };
    }

    private String filtroData(IAConsulta consulta) {
        return "data >= DATE '" + consulta.inicio() + "' AND data < DATE '" + consulta.fim().plusDays(1) + "'";
    }

    private Map<String, Object> resumoVendas(int empresaId, String filtro) {
        return db.queryForMap("""
                SELECT COUNT(*) AS quantidade_vendas,
                       COALESCE(SUM(total), 0) AS total
                  FROM vendas
                 WHERE empresa_id = ? AND %s
                """.formatted(filtro), empresaId);
    }

    private List<Map<String, Object>> vendasLista(int empresaId, String filtro) {
        return db.queryForList("""
                SELECT v.id,
                       TO_CHAR(v.data, 'DD/MM/YYYY HH24:MI') AS data,
                       v.status,
                       v.total,
                       COALESCE(c.nome, 'Cliente não informado') AS cliente,
                       COALESCE(
                           STRING_AGG(
                               COALESCE(p.nome, 'Produto não informado') || ' x' || iv.quantidade::text,
                               ', ' ORDER BY iv.id
                           ),
                           'Sem itens'
                       ) AS itens,
                       COUNT(*) OVER() AS total_registros
                  FROM vendas v
             LEFT JOIN clientes c
                    ON c.id = v.cliente_id
                   AND c.empresa_id = v.empresa_id
             LEFT JOIN itens_venda iv
                    ON iv.venda_id = v.id
             LEFT JOIN produtos p
                    ON p.id = iv.produto_id
                   AND p.empresa_id = v.empresa_id
                 WHERE v.empresa_id = ?
                   AND %s
              GROUP BY v.id, v.data, v.status, v.total, c.nome
              ORDER BY v.data DESC, v.id DESC
                 LIMIT %d
                """.formatted(filtro, MAX_LISTA), empresaId);
    }

    private List<Map<String, Object>> vendasProdutos(int empresaId, IAConsulta consulta, String filtro, String ordem) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id AS produto_id,
                       p.nome,
                       COALESCE(SUM(iv.quantidade), 0) AS quantidade,
                       COALESCE(SUM(iv.quantidade * iv.preco_unitario), 0) AS valor
                  FROM vendas v
                  JOIN itens_venda iv ON iv.venda_id = v.id
                  JOIN produtos p
                    ON p.id = iv.produto_id
                   AND p.empresa_id = v.empresa_id
                 WHERE v.empresa_id = ?
                   AND %s
                """.formatted(filtro));

        List<Object> params = new ArrayList<>();
        params.add(empresaId);
        adicionarFiltroProdutos(sql, params, consulta.produtos());

        sql.append(" GROUP BY p.id, p.nome ORDER BY quantidade ")
                .append(ordem)
                .append(", valor ")
                .append(ordem)
                .append(", p.nome LIMIT ")
                .append(MAX_LISTA);

        return db.queryForList(sql.toString(), params.toArray());
    }

    private List<Map<String, Object>> produtoPreco(int empresaId, List<String> produtosSolicitados) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id,
                       p.nome,
                       p.preco,
                       COALESCE(c.nome, 'Sem Categoria') AS categoria
                  FROM produtos p
             LEFT JOIN categorias c
                    ON c.id = p.categoria_id
                   AND c.empresa_id = p.empresa_id
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
                """);

        List<Object> params = new ArrayList<>();
        params.add(empresaId);

        if (produtosSolicitados != null && !produtosSolicitados.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < produtosSolicitados.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("LOWER(p.nome) LIKE LOWER(?)");
                params.add("%" + produtosSolicitados.get(i) + "%");
            }
            sql.append(")");
        }

        sql.append(" ORDER BY p.nome LIMIT 10");
        return db.queryForList(sql.toString(), params.toArray());
    }

    private void adicionarFiltroProdutos(StringBuilder sql, List<Object> params, List<String> produtos) {
        if (produtos == null || produtos.isEmpty()) return;
        sql.append(" AND (");
        for (int i = 0; i < produtos.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("LOWER(p.nome) LIKE LOWER(?)");
            params.add("%" + produtos.get(i) + "%");
        }
        sql.append(")");
    }

    private List<Map<String, Object>> produtosSemVendas(int empresaId, String filtro) {
        return db.queryForList("""
                SELECT p.id,
                       p.nome,
                       0 AS quantidade_vendida,
                       0 AS valor_vendido
                  FROM produtos p
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
                   AND NOT EXISTS (
                       SELECT 1
                         FROM itens_venda iv
                         JOIN vendas v ON v.id = iv.venda_id
                        WHERE iv.produto_id = p.id
                          AND v.empresa_id = p.empresa_id
                          AND %s
                   )
              ORDER BY p.nome
                 LIMIT %d
                """.formatted(filtro.replace("data", "v.data"), MAX_LISTA), empresaId);
    }

    private Map<String, Object> resumoCompras(int empresaId, String filtro) {
        return db.queryForMap("""
                SELECT COUNT(*) AS quantidade_compras,
                       COALESCE(SUM(total), 0) AS total
                  FROM compras
                 WHERE empresa_id = ? AND %s
                """.formatted(filtro), empresaId);
    }

    private List<Map<String, Object>> comprasLista(int empresaId, String filtro) {
        return db.queryForList("""
                SELECT c.id,
                       TO_CHAR(c.data, 'DD/MM/YYYY HH24:MI') AS data,
                       c.status,
                       c.total,
                       COALESCE(f.nome, 'Fornecedor não informado') AS fornecedor,
                       COUNT(*) OVER() AS total_registros
                  FROM compras c
             LEFT JOIN fornecedores f
                    ON f.id = c.fornecedor_id
                   AND f.empresa_id = c.empresa_id
                 WHERE c.empresa_id = ?
                   AND %s
              ORDER BY c.data DESC, c.id DESC
                 LIMIT %d
                """.formatted(filtro, MAX_LISTA), empresaId);
    }

    private List<Map<String, Object>> comprasProdutos(int empresaId, String filtro) {
        return db.queryForList("""
                SELECT p.id AS produto_id,
                       p.nome,
                       COALESCE(SUM(ic.quantidade), 0) AS quantidade,
                       COALESCE(SUM(ic.quantidade * ic.preco_unitario), 0) AS valor
                  FROM compras c
                  JOIN itens_compra ic ON ic.compra_id = c.id
                  JOIN produtos p
                    ON p.id = ic.produto_id
                   AND p.empresa_id = c.empresa_id
                 WHERE c.empresa_id = ?
                   AND %s
              GROUP BY p.id, p.nome
              ORDER BY quantidade DESC, valor DESC, p.nome
                 LIMIT %d
                """.formatted(filtro, MAX_LISTA), empresaId);
    }

    private List<Map<String, Object>> estoqueBaixo(int empresaId) {
        return db.queryForList("""
                SELECT p.id,
                       p.nome,
                       COALESCE(e.quantidade, 0) AS quantidade,
                       COALESCE(e.estoque_minimo, 0) AS minimo
                  FROM produtos p
             LEFT JOIN estoque e
                    ON e.produto_id = p.id
                   AND e.empresa_id = p.empresa_id
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
                   AND COALESCE(e.quantidade, 0) <= COALESCE(e.estoque_minimo, 0)
              ORDER BY quantidade, p.nome
                 LIMIT %d
                """.formatted(MAX_LISTA), empresaId);
    }

    private List<Map<String, Object>> estoqueLista(int empresaId) {
        return db.queryForList("""
                SELECT p.id,
                       p.nome,
                       COALESCE(e.quantidade, 0) AS quantidade,
                       COALESCE(e.estoque_minimo, 0) AS minimo,
                       p.preco
                  FROM produtos p
             LEFT JOIN estoque e
                    ON e.produto_id = p.id
                   AND e.empresa_id = p.empresa_id
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
              ORDER BY p.nome
                 LIMIT %d
                """.formatted(MAX_LISTA), empresaId);
    }

    private List<Map<String, Object>> produtos(int empresaId) {
        return db.queryForList("""
                SELECT p.id,
                       p.nome,
                       p.codigo,
                       p.preco,
                       COALESCE(c.nome, 'Sem Categoria') AS categoria,
                       COALESCE(e.quantidade, 0) AS estoque,
                       COALESCE(e.estoque_minimo, 0) AS estoque_minimo
                  FROM produtos p
             LEFT JOIN categorias c
                    ON c.id = p.categoria_id
                   AND c.empresa_id = p.empresa_id
             LEFT JOIN estoque e
                    ON e.produto_id = p.id
                   AND e.empresa_id = p.empresa_id
                 WHERE p.empresa_id = ?
                   AND p.ativo = true
              ORDER BY p.nome
                 LIMIT %d
                """.formatted(MAX_LISTA), empresaId);
    }

    private Map<String, Object> clientesResumo(int empresaId, String filtro) {
        return db.queryForMap("""
                SELECT COUNT(DISTINCT v.cliente_id) FILTER (WHERE v.cliente_id IS NOT NULL) AS clientes_compradores,
                       COALESCE(SUM(v.total), 0) AS total_vendido
                  FROM vendas v
                 WHERE v.empresa_id = ? AND %s
                """.formatted(filtro), empresaId);
    }

    private List<Map<String, Object>> clientesRanking(int empresaId, String filtro) {
        return db.queryForList("""
                SELECT c.nome AS cliente,
                       COUNT(v.id) AS quantidade_vendas,
                       COALESCE(SUM(v.total), 0) AS total_comprado
                  FROM clientes c
                  JOIN vendas v
                    ON v.cliente_id = c.id
                   AND v.empresa_id = c.empresa_id
                 WHERE c.empresa_id = ?
                   AND %s
              GROUP BY c.id, c.nome
              ORDER BY total_comprado DESC, c.nome
                 LIMIT %d
                """.formatted(filtro.replace("data", "v.data"), MAX_LISTA), empresaId);
    }

    private Map<String, Object> fornecedoresResumo(int empresaId, String filtro) {
        return db.queryForMap("""
                SELECT COUNT(DISTINCT c.fornecedor_id) FILTER (WHERE c.fornecedor_id IS NOT NULL) AS fornecedores_utilizados,
                       COALESCE(SUM(c.total), 0) AS total_comprado
                  FROM compras c
                 WHERE c.empresa_id = ? AND %s
                """.formatted(filtro), empresaId);
    }

    /**
     * Faz as comparações com BigDecimal no backend.
     */
    private Map<String, Object> construirComparacoes(List<Map<String, Object>> resultados) {
        Map<String, Object> saida = new LinkedHashMap<>();

        List<Map<String, Object>> resumosVendas = resultados.stream()
                .filter(r -> "VENDAS_RESUMO".equals(r.get("ferramenta")))
                .toList();

        if (resumosVendas.size() >= 2) {
            Map<String, Object> a = resumosVendas.get(0);
            Map<String, Object> b = resumosVendas.get(1);
            Map<String, Object> da = castMap(a.get("dados"));
            Map<String, Object> db = castMap(b.get("dados"));

            BigDecimal va = decimal(da.get("total"));
            BigDecimal vb = decimal(db.get("total"));
            BigDecimal diferenca = vb.subtract(va);

            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("periodo_1", a.get("inicio"));
            comp.put("periodo_2", b.get("inicio"));
            comp.put("total_1", va);
            comp.put("total_2", vb);
            comp.put("diferenca", diferenca);
            comp.put("variacao_percentual", percentual(diferenca, va));
            saida.put("faturamento_comparacao", comp);
        }

        List<Map<String, Object>> produtos = resultados.stream()
                .filter(r -> Set.of(
                        "VENDAS_PRODUTOS",
                        "PRODUTOS_MAIS_VENDIDOS",
                        "PRODUTOS_MENOS_VENDIDOS"
                ).contains(r.get("ferramenta")))
                .toList();

        if (produtos.size() >= 2) {
            Map<String, Map<String, Object>> primeiro = indexarProdutos(castList(produtos.get(0).get("dados")));
            Map<String, Map<String, Object>> segundo = indexarProdutos(castList(produtos.get(1).get("dados")));

            Set<String> ids = new LinkedHashSet<>();
            ids.addAll(primeiro.keySet());
            ids.addAll(segundo.keySet());

            List<Map<String, Object>> linhas = new ArrayList<>();
            for (String id : ids) {
                Map<String, Object> p1 = primeiro.getOrDefault(id, Map.of());
                Map<String, Object> p2 = segundo.getOrDefault(id, Map.of());

                BigDecimal q1 = decimal(p1.get("quantidade"));
                BigDecimal q2 = decimal(p2.get("quantidade"));
                BigDecimal v1 = decimal(p1.get("valor"));
                BigDecimal v2 = decimal(p2.get("valor"));

                Map<String, Object> linha = new LinkedHashMap<>();
                linha.put("produto_id", id);
                linha.put("produto", p2.getOrDefault("nome", p1.getOrDefault("nome", "Produto")));
                linha.put("quantidade_periodo_1", q1);
                linha.put("quantidade_periodo_2", q2);
                linha.put("valor_periodo_1", v1);
                linha.put("valor_periodo_2", v2);
                linha.put("diferenca_quantidade", q2.subtract(q1));
                linha.put("diferenca_valor", v2.subtract(v1));
                linha.put("variacao_percentual_valor", percentual(v2.subtract(v1), v1));
                linhas.add(linha);
            }

            saida.put("produtos_comparacao", linhas);
        }

        return saida;
    }

    private Map<String, Map<String, Object>> indexarProdutos(List<?> dados) {
        Map<String, Map<String, Object>> mapa = new LinkedHashMap<>();
        for (Object item : dados) {
            Map<String, Object> linha = castMap(item);
            Object id = linha.get("produto_id");
            if (id == null) id = linha.get("id");
            if (id == null) continue;
            mapa.put(String.valueOf(id), linha);
        }
        return mapa;
    }

    /** Resposta factual: dados e cálculos vêm do backend, não do Gemma. */
    private String respostaFactual(IAPlano plano, Map<String, Object> contexto) {
        List<String> partes = new ArrayList<>();
        int indice = 1;

        for (IAConsulta consulta : plano.consultas()) {
            Map<String, Object> pacote = castMap(contexto.get("consulta_" + indice));
            if (pacote.isEmpty()) {
                indice++;
                continue;
            }

            Object dados = pacote.get("dados");
            String ferramenta = String.valueOf(pacote.get("ferramenta"));
            String inicio = String.valueOf(pacote.get("inicio"));
            String fim = String.valueOf(pacote.get("fim"));

            switch (ferramenta) {
                case "VENDAS_RESUMO" -> {
                    Map<String, Object> m = castMap(dados);
                    partes.add("Vendas de " + formatarPeriodo(inicio, fim) + ": "
                            + m.getOrDefault("quantidade_vendas", 0) + " venda(s), total de " + moeda(m.get("total")) + ".");
                }
                case "VENDAS_LISTA" -> partes.add(formatarListaVenda(castList(dados), inicio, fim));
                case "VENDAS_PRODUTOS" -> partes.add(formatarProdutosVendidos(castList(dados), "Produtos vendidos em " + formatarPeriodo(inicio, fim) + ":"));
                case "PRODUTOS_MAIS_VENDIDOS" -> partes.add(formatarProdutosVendidos(castList(dados), "Produtos mais vendidos em " + formatarPeriodo(inicio, fim) + ":"));
                case "PRODUTOS_MENOS_VENDIDOS" -> partes.add(formatarProdutosVendidos(castList(dados), "Produtos menos vendidos em " + formatarPeriodo(inicio, fim) + ":"));
                case "PRODUTOS_SEM_VENDAS" -> partes.add(formatarSemVendas(castList(dados), formatarPeriodo(inicio, fim)));
                case "PRODUTO_PRECO" -> partes.add(formatarPrecos(castList(dados)));
                case "COMPRAS_RESUMO" -> {
                    Map<String, Object> m = castMap(dados);
                    partes.add("Compras de " + formatarPeriodo(inicio, fim) + ": "
                            + m.getOrDefault("quantidade_compras", 0) + " compra(s), total de " + moeda(m.get("total")) + ".");
                }
                case "COMPRAS_LISTA" -> partes.add(formatarListaCompra(castList(dados), inicio, fim));
                case "COMPRAS_PRODUTOS" -> partes.add(formatarProdutosVendidos(castList(dados), "Produtos comprados em " + formatarPeriodo(inicio, fim) + ":"));
                case "ESTOQUE_BAIXO" -> partes.add(formatarEstoqueBaixo(castList(dados)));
                case "ESTOQUE_LISTA" -> partes.add(formatarEstoque(castList(dados)));
                case "PRODUTOS" -> partes.add(formatarCatalogo(castList(dados)));
                case "CLIENTES_RESUMO" -> {
                    Map<String, Object> m = castMap(dados);
                    partes.add("No período, " + m.getOrDefault("clientes_compradores", 0)
                            + " cliente(s) realizaram compras, totalizando " + moeda(m.get("total_vendido")) + ".");
                }
                case "CLIENTES_RANKING" -> partes.add(formatarClientes(castList(dados)));
                case "FORNECEDORES_RESUMO" -> {
                    Map<String, Object> m = castMap(dados);
                    partes.add("No período, foram utilizados " + m.getOrDefault("fornecedores_utilizados", 0)
                            + " fornecedor(es), com compras totalizando " + moeda(m.get("total_comprado")) + ".");
                }
                default -> { }
            }
            indice++;
        }

        String comparacoes = formatarComparacoes(contexto.get("comparacoes_calculadas"));
        if (!comparacoes.isBlank()) partes.add(comparacoes);
        return partes.isEmpty() ? null : String.join("\n\n", partes);
    }

    private Map<String, Object> localizarPacote(Map<String, Object> contexto, IAConsulta consulta, int indiceFallback) {
        String prefixo = "consulta_" + (indiceFallback + 1);
        Map<String, Object> direto = castMap(contexto.get(prefixo));
        if (!direto.isEmpty()) return direto;
        for (Map.Entry<String, Object> entry : contexto.entrySet()) {
            Map<String, Object> pacote = castMap(entry.getValue());
            if (consulta.ferramenta().name().equals(String.valueOf(pacote.get("ferramenta")))) {
                return pacote;
            }
        }
        return Map.of();
    }

    private String formatarListaVenda(List<?> linhas, String inicio, String fim) {
        if (linhas.isEmpty()) return "Não encontrei vendas de " + formatarPeriodo(inicio, fim) + ".";

        int total = numeroInteiro(castMap(linhas.get(0)).get("total_registros"));
        StringBuilder sb = new StringBuilder("Vendas de ").append(formatarPeriodo(inicio, fim)).append(":\n");
        for (Object item : linhas) {
            Map<String, Object> m = castMap(item);
            sb.append("- Venda #").append(m.get("id"))
                    .append(" | ").append(m.get("data"))
                    .append(" | Cliente: ").append(m.get("cliente"))
                    .append(" | Total: ").append(moeda(m.get("total")))
                    .append(" | Itens: ").append(m.getOrDefault("itens", "Sem itens"))
                    .append(" | Status: ").append(m.getOrDefault("status", "não informado"))
                    .append('\n');
        }
        if (total > linhas.size()) {
            sb.append("Mostrando ").append(linhas.size()).append(" de ").append(total).append(" vendas; o limite da consulta é ").append(MAX_LISTA).append(" registros.");
        } else {
            sb.append("Total encontrado: ").append(total).append(" venda(s).");
        }
        return sb.toString().trim();
    }

    private String formatarListaCompra(List<?> linhas, String inicio, String fim) {
        if (linhas.isEmpty()) return "Não encontrei compras de " + formatarPeriodo(inicio, fim) + ".";

        int total = numeroInteiro(castMap(linhas.get(0)).get("total_registros"));
        StringBuilder sb = new StringBuilder("Compras de ").append(formatarPeriodo(inicio, fim)).append(":\n");
        for (Object item : linhas) {
            Map<String, Object> m = castMap(item);
            sb.append("- Compra #").append(m.get("id"))
                    .append(" | ").append(m.get("data"))
                    .append(" | Fornecedor: ").append(m.get("fornecedor"))
                    .append(" | Total: ").append(moeda(m.get("total")))
                    .append(" | Status: ").append(m.getOrDefault("status", "não informado"))
                    .append('\n');
        }
        if (total > linhas.size()) {
            sb.append("Mostrando ").append(linhas.size()).append(" de ").append(total).append(" compras.");
        } else {
            sb.append("Total encontrado: ").append(total).append(" compra(s).");
        }
        return sb.toString().trim();
    }

    private String formatarProdutosVendidos(List<?> itens, String titulo) {
        if (itens.isEmpty()) return titulo + "\n- Nenhum registro encontrado.";
        StringBuilder sb = new StringBuilder(titulo).append('\n');
        int limite = Math.min(itens.size(), MAX_LISTA);
        for (int i = 0; i < limite; i++) {
            Map<String, Object> m = castMap(itens.get(i));
            Object id = m.getOrDefault("produto_id", m.get("id"));
            sb.append("- Produto #").append(id)
                    .append(" — ").append(m.get("nome"))
                    .append(" | Quantidade: ").append(m.getOrDefault("quantidade", 0))
                    .append(" | Valor: ").append(moeda(m.getOrDefault("valor", 0)))
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String formatarPrecos(List<?> itens) {
        if (itens.isEmpty()) return "Não encontrei um produto com esse nome no catálogo ativo.";

        StringBuilder sb = new StringBuilder("Preço cadastrado:");
        for (Object item : itens) {
            Map<String, Object> m = castMap(item);
            sb.append("\n- Produto #").append(m.get("id"))
                    .append(" — ").append(m.get("nome"))
                    .append(" | Preço: ").append(moeda(m.get("preco")))
                    .append(" | Categoria: ").append(m.getOrDefault("categoria", "Sem Categoria"));
        }
        return sb.toString();
    }

    private String formatarSemVendas(List<?> itens, String periodo) {
        if (itens.isEmpty()) return "Não encontrei produtos sem vendas em " + periodo + ".";
        StringBuilder sb = new StringBuilder("Produtos sem vendas em ").append(periodo).append(":\n");
        for (Object item : itens) {
            Map<String, Object> m = castMap(item);
            sb.append("- Produto #").append(m.get("id"))
                    .append(" — ").append(m.get("nome"))
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String formatarEstoqueBaixo(List<?> itens) {
        if (itens.isEmpty()) return "Não há produtos no estoque mínimo ou abaixo dele.";
        StringBuilder sb = new StringBuilder("Produtos com estoque baixo:\n");
        for (Object item : itens) {
            Map<String, Object> m = castMap(item);
            sb.append("- Produto #").append(m.get("id"))
                    .append(" — ").append(m.get("nome"))
                    .append(" | Estoque: ").append(m.getOrDefault("quantidade", 0))
                    .append(" | Mínimo: ").append(m.getOrDefault("minimo", 0))
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String formatarEstoque(List<?> itens) {
        if (itens.isEmpty()) return "Não encontrei produtos no estoque.";
        StringBuilder sb = new StringBuilder("Estoque atual:\n");
        for (Object item : itens) {
            Map<String, Object> m = castMap(item);
            sb.append("- Produto #").append(m.get("id"))
                    .append(" — ").append(m.get("nome"))
                    .append(" | Quantidade: ").append(m.getOrDefault("quantidade", 0))
                    .append(" | Mínimo: ").append(m.getOrDefault("minimo", 0))
                    .append(" | Preço: ").append(moeda(m.get("preco")))
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String formatarCatalogo(List<?> itens) {
        if (itens.isEmpty()) return "Não encontrei produtos ativos.";
        StringBuilder sb = new StringBuilder("Produtos ativos:\n");
        for (Object item : itens) {
            Map<String, Object> m = castMap(item);
            sb.append("- Produto #").append(m.get("id"))
                    .append(" — ").append(m.get("nome"))
                    .append(" | Categoria: ").append(m.getOrDefault("categoria", "Sem Categoria"))
                    .append(" | Preço: ").append(moeda(m.get("preco")))
                    .append(" | Estoque: ").append(m.getOrDefault("estoque", 0))
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String formatarClientes(List<?> itens) {
        if (itens.isEmpty()) return "Não encontrei clientes compradores no período.";
        StringBuilder sb = new StringBuilder("Clientes por total comprado:\n");
        for (Object item : itens) {
            Map<String, Object> m = castMap(item);
            sb.append("- ").append(m.get("cliente"))
                    .append(" | Vendas: ").append(m.getOrDefault("quantidade_vendas", 0))
                    .append(" | Total: ").append(moeda(m.get("total_comprado")))
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String formatarComparacoes(Object valor) {
        Map<String, Object> mapa = castMap(valor);
        if (mapa.isEmpty()) return "";

        List<String> partes = new ArrayList<>();
        Map<String, Object> fat = castMap(mapa.get("faturamento_comparacao"));
        if (!fat.isEmpty()) {
            partes.add("Comparação de faturamento: "
                    + fat.get("periodo_1") + " = " + moeda(fat.get("total_1"))
                    + " | " + fat.get("periodo_2") + " = " + moeda(fat.get("total_2"))
                    + " | diferença = " + moeda(fat.get("diferenca"))
                    + " | variação = " + percentualTexto(fat.get("variacao_percentual")) + ".");
        }

        List<?> produtos = castList(mapa.get("produtos_comparacao"));
        if (!produtos.isEmpty()) {
            StringBuilder sb = new StringBuilder("Comparação por produto:");
            int limite = Math.min(produtos.size(), MAX_EXIBICAO_COMPARACAO);
            for (int i = 0; i < limite; i++) {
                Map<String, Object> p = castMap(produtos.get(i));
                sb.append("\n- Produto #").append(p.get("produto_id"))
                        .append(" — ").append(p.get("produto"))
                        .append(" | período 1: ").append(p.get("quantidade_periodo_1"))
                        .append(" un.")
                        .append(" | período 2: ").append(p.get("quantidade_periodo_2"))
                        .append(" un.")
                        .append(" | diferença: ").append(p.get("diferenca_quantidade"))
                        .append(" un.")
                        .append(" | valor no período 1: ").append(moeda(p.get("valor_periodo_1")))
                        .append(" | valor no período 2: ").append(moeda(p.get("valor_periodo_2")))
                        .append(" | diferença em valor: ").append(moeda(p.get("diferenca_valor")))
                        .append(" | variação: ").append(percentualTexto(p.get("variacao_percentual_valor")));
            }
            if (produtos.size() > limite) sb.append("\n... ").append(produtos.size() - limite).append(" produto(s) não exibido(s).");
            partes.add(sb.toString());
        }

        return String.join("\n\n", partes);
    }

    /**
     * Só chama o modelo para análise qualitativa. Os números já foram calculados pelo backend.
     */
    private String gerarAnaliseSemRecalculo(String pergunta, List<IAHistoricoItem> historico, Map<String, Object> contexto) {
        String prompt = """
                Você é um analista de um ERP brasileiro.

                Analise APENAS os dados oficiais abaixo.
                O backend já calculou todos os valores, diferenças e percentuais.
                Não faça nenhuma conta.
                Não crie nenhum número, data, ID, produto, cliente ou fornecedor.
                Não introduza nenhuma informação que não esteja no contexto.
                Não cite valores monetários, percentuais, quantidades ou datas na sua análise.
                Escreva no máximo 3 frases curtas, explicando a tendência ou o que merece atenção.
                Se não houver evidência suficiente, responda apenas: "Os dados disponíveis não permitem uma análise segura.".

                PERGUNTA:
                %s

                HISTÓRICO:
                %s

                DADOS OFICIAIS:
                %s
                """.formatted(pergunta, formatarHistorico(historico), serializar(contexto));

        return ollama.gerar(prompt);
    }

    private boolean analiseSegura(String resposta) {
        String s = normalizar(resposta);
        if (s.isBlank()) return false;
        if (s.contains("r$") || s.matches(".*\\d+.*") || s.contains("%")) return false;
        if (s.contains("sql") || s.contains("banco de dados") || s.contains("prompt") || s.contains("ferramenta")) return false;
        return resposta.length() <= 900;
    }

    private boolean pedeAnalise(String pergunta) {
        String p = normalizar(pergunta);
        return p.contains("analise") || p.contains("por que") || p.contains("porque")
                || p.contains("como estao") || p.contains("como esta") || p.contains("tendencia")
                || p.contains("merece atencao") || p.contains("o que explica") || p.contains("explique")
                || p.contains("comparar") || p.contains("comparacao") || p.contains("compare");
    }

    private String respostaFallback(IAPlano plano, Map<String, Object> contexto) {
        return "Encontrei os dados do ERP, mas não consegui montar uma resposta textual confiável para essa pergunta.";
    }

    private boolean pareceSerPerguntaDoERP(String pergunta, List<IAHistoricoItem> historico) {
        StringBuilder texto = new StringBuilder(normalizar(pergunta));
        if (historico != null) {
            historico.stream()
                    .filter(Objects::nonNull)
                    .limit(MAX_HISTORICO)
                    .forEach(h -> texto.append(' ').append(normalizar(h.conteudo() == null ? "" : h.conteudo())));
        }

        String base = texto.toString();
        if (base.contains("erp") || base.contains("assistente") || base.contains("meu negocio") || base.contains("minha empresa")) return true;
        return base.matches(".*\\b(venda|vendas|vendi|faturamento|faturou|receita|compra|compras|comprei|estoque|produto|produtos|cliente|clientes|fornecedor|fornecedores|categoria|categorias|preco|precos|mercadoria)\\b.*");
    }

    private String bloquearAcaoOuDadoSensivel(String pergunta) {
        String p = normalizar(pergunta);
        if (contemDadoSensivel(p)) {
            return "Não posso consultar, exibir ou recuperar credenciais, documentos ou dados pessoais sensíveis pelo assistente.";
        }
        if (contemAcaoEscrita(p)) {
            return "O assistente é somente de consulta. Ele não pode criar, editar, excluir, cancelar ou alterar dados do ERP.";
        }
        return null;
    }

    private boolean contemDadoSensivel(String p) {
        return p.contains("senha") || p.contains("senha_hash") || p.contains("token") || p.contains("bearer")
                || p.contains("api key") || p.contains("apikey") || p.contains("credencial")
                || p.contains("segredo") || p.contains("chave secreta") || p.contains("cpf")
                || p.contains("cnpj") || p.contains("documento") || p.contains("telefone")
                || p.contains("celular") || p.contains("endereco") || p.contains("email") || p.contains("e-mail");
    }

    private boolean contemAcaoEscrita(String p) {
        return p.matches(".*\\b(criar|crie|cria|adicionar|adicione|adiciona|editar|edite|edita|alterar|altere|altera|atualizar|atualize|atualiza|excluir|exclua|exclui|apagar|apague|apaga|deletar|delete|deletar|registrar|registre|registra|cancelar|cancele|cancela|remover|remova|remove|mudar|mude|muda)\\b.*")
                || p.contains("dar baixa")
                || p.contains("ajustar estoque")
                || p.contains("baixar estoque")
                || p.contains("corrigir estoque");
    }

    private String respostaForaEscopo() {
        return "Posso ajudar somente com consultas de leitura do ERP, como vendas, compras, faturamento, produtos, estoque e análises operacionais. Não posso alterar dados nem acessar credenciais ou dados pessoais sensíveis.";
    }

    private List<IAHistoricoItem> limitarHistorico(List<IAHistoricoItem> historico) {
        if (historico == null || historico.isEmpty()) return List.of();
        return historico.stream().filter(Objects::nonNull).limit(MAX_HISTORICO).toList();
    }

    private String formatarHistorico(List<IAHistoricoItem> historico) {
        if (historico == null || historico.isEmpty()) return "Nenhum histórico anterior.";
        return historico.stream()
                .filter(Objects::nonNull)
                .limit(MAX_HISTORICO)
                .map(h -> {
                    String papel = "assistant".equalsIgnoreCase(h.papel()) ? "assistente" : "usuário";
                    String conteudo = h.conteudo() == null ? "" : h.conteudo().trim();
                    return papel + ": " + cortar(conteudo, 1000);
                })
                .collect(Collectors.joining("\n"));
    }

    private Intervalo normalizarIntervalo(LocalDate inicio, LocalDate fim) {
        LocalDate hoje = hoje();
        LocalDate a = inicio == null ? hoje.withDayOfMonth(1) : inicio;
        LocalDate b = fim == null ? hoje.withDayOfMonth(hoje.lengthOfMonth()) : fim;
        if (b.isBefore(a)) return null;
        return new Intervalo(a, b);
    }

    private List<Periodo> extrairPeriodos(String p) {
        LinkedHashSet<Periodo> saida = new LinkedHashSet<>();
        LocalDate hoje = hoje();

        if (p.contains("hoje")) saida.add(new Periodo(hoje, hoje));
        if (p.contains("ontem")) {
            LocalDate ontem = hoje.minusDays(1);
            saida.add(new Periodo(ontem, ontem));
        }
        if (p.contains("ultimos 7 dias") || p.contains("ultima semana")) {
            saida.add(new Periodo(hoje.minusDays(6), hoje));
        }
        if (p.contains("ultimos 30 dias")) {
            saida.add(new Periodo(hoje.minusDays(29), hoje));
        }

        Matcher meses = Pattern.compile("\\b(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\\b").matcher(p);
        int anoPadrao = extrairAno(p, hoje.getYear());
        while (meses.find()) {
            Month mes = MESES.get(meses.group(1));
            YearMonth ym = YearMonth.of(anoPadrao, mes);
            saida.add(new Periodo(ym.atDay(1), ym.atEndOfMonth()));
        }

        if (p.contains("mes passado") || p.contains("ultimo mes")) {
            YearMonth ym = YearMonth.from(hoje).minusMonths(1);
            saida.add(new Periodo(ym.atDay(1), ym.atEndOfMonth()));
        }
        if (p.contains("mes retrasado")) {
            YearMonth ym = YearMonth.from(hoje).minusMonths(2);
            saida.add(new Periodo(ym.atDay(1), ym.atEndOfMonth()));
        }
        if (p.contains("dois meses") || p.contains("2 meses")) {
            YearMonth atual = YearMonth.from(hoje);
            YearMonth anterior = atual.minusMonths(1);
            saida.add(new Periodo(anterior.atDay(1), anterior.atEndOfMonth()));
            saida.add(new Periodo(atual.atDay(1), atual.atEndOfMonth()));
        }
        if (p.contains("este mes") || p.contains("neste mes") || p.contains("mes atual")) {
            YearMonth ym = YearMonth.from(hoje);
            saida.add(new Periodo(ym.atDay(1), ym.atEndOfMonth()));
        }

        if (saida.isEmpty()) {
            YearMonth ym = YearMonth.from(hoje);
            saida.add(new Periodo(ym.atDay(1), ym.atEndOfMonth()));
        }

        return new ArrayList<>(saida);
    }

    private Periodo periodoMesAtual() {
        YearMonth ym = YearMonth.from(hoje());
        return new Periodo(ym.atDay(1), ym.atEndOfMonth());
    }

    private int extrairAno(String p, int padrao) {
        Matcher matcher = Pattern.compile("\\b(20\\d{2})\\b").matcher(p);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : padrao;
    }

    private String perguntasSemMeses(String p) {
        String retorno = p;
        for (String mes : MESES.keySet()) retorno = retorno.replace(mes, " ");
        return retorno.replaceAll("\\b20\\d{2}\\b", " ");
    }

    private List<String> extrairNomesProdutosObvios(String p) {
        if (p == null || p.isBlank()) return List.of();
        Matcher matcher = Pattern.compile("produto\\s+([a-z0-9][a-z0-9 _-]{1,80}?)(?=\\s+(?:em|no|na|nos|nas|de|do|da|dos|das|este|esse|e|ou|que|compar|vendeu|venda|vendido)|$)").matcher(p);
        List<String> encontrados = new ArrayList<>();
        while (matcher.find() && encontrados.size() < 5) {
            String nome = matcher.group(1).trim();
            if (!nome.isBlank()) encontrados.add(nome);
        }
        return encontrados;
    }

    private LocalDate hoje() {
        return LocalDate.now(FUSO_ERP);
    }

    private boolean contemPalavra(String texto, String palavra) {
        return texto.matches(".*\\b" + Pattern.quote(palavra) + "\\b.*");
    }

    private LocalDate dataOuNula(String valor) {
        if (valor == null || valor.isBlank()) return null;
        try {
            return LocalDate.parse(valor, DATA);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String limparJson(String texto) {
        return JSON_CODE_FENCE.matcher(texto == null ? "" : texto.trim()).replaceFirst("").trim();
    }

    private String limparResposta(String texto) {
        String s = texto == null ? "" : texto.trim();
        if (s.startsWith("```") && s.endsWith("```")) {
            s = s.substring(3, s.length() - 3).trim();
            if (s.startsWith("markdown")) s = s.substring(8).trim();
        }
        return s;
    }

    private String serializar(Object valor) {
        try {
            return mapper.writeValueAsString(valor);
        } catch (Exception e) {
            return String.valueOf(valor);
        }
    }

    private Map<String, Object> castMap(Object valor) {
        if (valor instanceof Map<?, ?> mapa) {
            Map<String, Object> saida = new LinkedHashMap<>();
            mapa.forEach((k, v) -> saida.put(String.valueOf(k), v));
            return saida;
        }
        return Map.of();
    }

    private List<?> castList(Object valor) {
        return valor instanceof List<?> lista ? lista : List.of();
    }

    private List<String> lerStrings(JsonNode node, int limite, int tamanhoMax) {
        if (node == null || !node.isArray()) return new ArrayList<>();
        List<String> saida = new ArrayList<>();
        for (JsonNode item : node) {
            String valor = item.asText("").trim();
            if (!valor.isBlank() && valor.length() <= tamanhoMax && saida.size() < limite) saida.add(valor);
        }
        return saida;
    }

    private BigDecimal decimal(Object valor) {
        if (valor == null) return BigDecimal.ZERO;
        if (valor instanceof BigDecimal bd) return bd;
        if (valor instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(String.valueOf(valor).replace(',', '.'));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String moeda(Object valor) {
        return "R$ " + decimal(valor)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString()
                .replace('.', ',');
    }

    private BigDecimal percentual(BigDecimal diferenca, BigDecimal base) {
        if (base == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
        return diferenca.multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP);
    }

    private String percentualTexto(Object valor) {
        if (valor == null) return "não calculável";
        return decimal(valor).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "%";
    }

    private int numeroInteiro(Object valor) {
        try {
            return Integer.parseInt(String.valueOf(valor));
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatarPeriodo(String inicio, String fim) {
        try {
            LocalDate a = LocalDate.parse(inicio, DATA);
            LocalDate b = LocalDate.parse(fim, DATA);
            if (a.equals(b)) return a.format(DATA_BR);
            return a.format(DATA_BR) + " a " + b.format(DATA_BR);
        } catch (Exception e) {
            return inicio + " a " + fim;
        }
    }

    private String normalizar(String s) {
        return Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private String cortar(String s, int limite) {
        return s.length() <= limite ? s : s.substring(0, limite) + "...";
    }

    private record Intervalo(LocalDate inicio, LocalDate fim) {}
    private record Periodo(LocalDate inicio, LocalDate fim) {}
}
