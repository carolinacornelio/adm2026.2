package com.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Arrays;

/** Cliente simples e seguro para o Ollama local. */
@Service
public class OllamaClient {

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final boolean enabled;
    private final Duration requestTimeout;
    private final OllamaRuntime runtime;

    public OllamaClient(
            ObjectMapper mapper,
            @Value("${erp.ia.ollama.url:http://localhost:11434}") String baseUrl,
            @Value("${erp.ia.ollama.model:gemma3:4b}") String model,
            @Value("${erp.ia.ollama.enabled:true}") boolean enabled,
            @Value("${erp.ia.ollama.timeout-seconds:180}") int timeoutSeconds) {
        this.mapper = mapper;
        this.runtime = new OllamaRuntime(baseUrl);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.enabled = enabled;
        this.requestTimeout = Duration.ofSeconds(Math.max(5, Math.min(timeoutSeconds, 180)));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Solicitação temporária de atividade, somente para inferência local. */
    public OllamaRuntime.Atividade manterComputadorAtivo() { return enabled ? runtime.manterAtivo() : new OllamaRuntime.Atividade(null); }

    /** O próprio modelo redige a análise e declara seu escopo no mesmo resultado estruturado. */
    public String gerar(String prompt) {
        String json = enviar(prompt + "\nRetorne JSON: permitido=true e resposta com a análise operacional. "
                        + "Se a resposta violar o escopo ERP, permitido=false e resposta vazia. "
                        + "Na propriedade resposta escreva somente a mensagem natural ao usuário; nunca inclua Permitido=true, "
                        + "nomes de campos, decisões de autorização ou comentários sobre este JSON.",
                true, List.of(), Map.of("type", "object", "additionalProperties", false,
                        "required", List.of("permitido", "resposta"), "properties", Map.of(
                                "permitido", Map.of("type", "boolean"),
                                "resposta", Map.of("type", "string", "maxLength", 6000))));
        if (json == null) return null;
        try {
            JsonNode resultado = mapper.readTree(json);
            if (!resultado.path("permitido").isBoolean() || !resultado.path("resposta").isTextual())
                throw new IllegalArgumentException();
            if (!resultado.path("permitido").booleanValue()) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "A IA pode responder somente sobre dados operacionais do ERP, sem dados pessoais ou detalhes internos.");
            String resposta = resultado.path("resposta").asText().trim();
            if (resposta.isBlank()) throw new IllegalArgumentException();
            return resposta;
        } catch (org.springframework.web.server.ResponseStatusException e) { throw e; }
        catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "O modelo retornou uma análise inválida. Tente uma pergunta mais específica.");
        }
    }

    /** Geração estruturada para o planejador. O Ollama recebe format=json. */
    public String gerarJson(String prompt) {
        return gerarJson(prompt, List.of());
    }

    public String gerarJson(String prompt, List<Integer> vendaIds) {
        return enviar(prompt, true, vendaIds);
    }

    private String enviar(String prompt, boolean jsonMode, List<Integer> vendaIds) {
        return enviar(prompt, jsonMode, vendaIds, jsonMode ? formatoPlano(vendaIds) : null);
    }

    private String enviar(String prompt, boolean jsonMode, List<Integer> vendaIds, Object formato) {
        return enviar(prompt, jsonMode, vendaIds, formato, false);
    }

    private String enviar(String prompt, boolean jsonMode, List<Integer> vendaIds, Object formato, boolean repeticao) {
        if (!enabled || prompt == null || prompt.isBlank()) return null;

        try {
            runtime.garantirDisponivel();
            long inicio = System.nanoTime();
            boolean redacao = formato instanceof Map<?, ?> schema && schema.get("properties") instanceof Map<?, ?> props && props.containsKey("resposta");
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("model", model);
            corpo.put("messages", List.of(
                    Map.of("role", "system", "content", "Siga as instruções da tarefa e utilize exclusivamente os dados fornecidos. Responda em português brasileiro."),
                    Map.of("role", "user", "content", prompt)));
            corpo.put("stream", false);
            corpo.put("keep_alive", "15m");
            corpo.put("options", Map.of(
                    "temperature", jsonMode ? 0.0 : 0.1,
                    "num_ctx", 6144,
                    "num_batch", 256,
                    "use_mmap", true,
                    "num_predict", redacao ? (repeticao ? 2000 : 1400) : (repeticao ? 1200 : 800)
            ));
            if (jsonMode) corpo.put("format", formato);

            String body = mapper.writeValueAsString(corpo);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .timeout(Duration.ofSeconds(Math.min(requestTimeout.toSeconds(), 90)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String mensagem = response.statusCode() == 404
                        ? "O modelo configurado não foi encontrado no Ollama. Instale gemma3:4b no servidor do ERP."
                        : response.statusCode() == 500
                        ? "O Ollama falhou ao executar o modelo. Pode faltar memória no servidor; feche aplicações pesadas e tente novamente."
                        : response.statusCode() == 503
                        ? "O Ollama está sem recursos ou ocupado. Aguarde e tente novamente."
                        : "O Ollama recusou a solicitação (HTTP " + response.statusCode() + "). Verifique o serviço local.";
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, mensagem);
            }

            JsonNode json = mapper.readTree(response.body());
            org.slf4j.LoggerFactory.getLogger(OllamaClient.class).info(
                    "IA Ollama: etapa={}, ms={}, carregamento_ms={}, entrada_tokens={}, saida_tokens={}",
                    redacao ? "resposta" : "plano", (System.nanoTime()-inicio)/1_000_000,
                    json.path("load_duration").asLong()/1_000_000, json.path("prompt_eval_count").asInt(), json.path("eval_count").asInt());
            JsonNode result = json.path("message").get("content");
            if ("length".equals(json.path("done_reason").asText())) {
                // Nunca entregue JSON parcial. Repita uma única vez, com mais espaço e instrução de síntese.
                if (!repeticao) return enviar(prompt + "\nConclua o JSON de forma concisa. Omita campos opcionais desnecessários. "
                        + "Resuma listas extensas e informe os limites; não invente nem omita silenciosamente períodos.",
                        jsonMode, vendaIds, formato, true);
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "O modelo não conseguiu concluir a análise nesta tentativa. Tente novamente ou consulte os períodos no histórico de vendas.");
            }
            if (result == null || result.isNull()) return null;

            String resposta = result.asText();
            return resposta == null || resposta.isBlank() ? null : resposta.trim();

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                    "O Gemma 3:4b excedeu o tempo de análise no computador do servidor. Aguarde a conclusão de outras consultas e tente novamente.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "A consulta à IA foi interrompida. Tente novamente.");
        } catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível obter uma resposta do Ollama. Verifique se ele está ativo no servidor ERP na porta 11434.");
        }
    }

    /** O modelo escolhe o plano; o schema restringe somente sua estrutura e ferramentas. */
    private Map<String, Object> formatoPlano(List<Integer> vendaIds) {
        Map<String, Object> campos = Map.of(
                "ferramenta", Map.of("type", "string", "enum", Arrays.stream(IATool.values())
                        .filter(tool -> tool != IATool.VENDA_COMPROVANTE && tool != IATool.CLIENTES_RESUMO
                                && tool != IATool.CLIENTES_RANKING && tool != IATool.FORNECEDORES_RESUMO).map(Enum::name).toList()),
                "inicio", Map.of("type", List.of("string", "null"), "pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", "maxLength", 10),
                "fim", Map.of("type", List.of("string", "null"), "pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", "maxLength", 10),
                "produtos", Map.of("type", "array", "maxItems", 10, "items", Map.of("type", "string", "maxLength", 150)),
                "dias", Map.of("type", "integer", "minimum", 0, "maximum", 365),
                "situacao", Map.of("type", "string", "enum", IATool.situacoesPlanejaveis()));
        Object consultaBase = Map.of("type", "object", "properties", campos, "required", List.of("ferramenta"), "additionalProperties", false);
        Object consulta = vendaIds.isEmpty() ? consultaBase : Map.of("anyOf", List.of(consultaBase,
                Map.of("type", "object", "properties", Map.of(
                        "ferramenta", Map.of("type", "string", "enum", List.of("VENDA_COMPROVANTE")),
                        "vendaId", Map.of("type", "integer", "enum", vendaIds)),
                        "required", List.of("ferramenta", "vendaId"), "additionalProperties", false)));
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("permitido", "consultas"),
                "properties", Map.of(
                        "permitido", Map.of("type", "boolean"),
                        "motivo", Map.of("type", "string", "maxLength", 200),
                        "consultas", Map.of("type", "array", "maxItems", 4, "items", consulta),
                        "calculos", Map.of("type", "array", "maxItems", 8, "items", Map.of("type", "string", "enum",
                                List.of("DIFERENCA_VALOR","DIFERENCA_QUANTIDADE","VARIACAO_PERCENTUAL","TOTAL","MAIOR","MENOR")))));
    }
}
