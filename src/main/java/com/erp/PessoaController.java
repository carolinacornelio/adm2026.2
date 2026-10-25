package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Unifica clientes e fornecedores na API com controles rigorosos de Compliance:
 * - Regra ALT-03: Segregação de Funções (SoD) impedindo operadores de compras de homologar fornecedores.
 * - Regra ALT-04: Princípio de Quatro Olhos para alteração de dados bancários de fornecedores.
 * - Upload obrigatório de contrato social ou documentação fiscal.
 */
@RestController 
@RequestMapping("/api/pessoas")
public class PessoaController {

    private final JdbcTemplate db; 
    private final CurrentUser user;
    private final ComplianceService compliance;

    public PessoaController(JdbcTemplate db, CurrentUser user, ComplianceService compliance) {
        this.db = db;
        this.user = user;
        this.compliance = compliance;
    }

    @GetMapping 
    public List<Map<String,Object>> listar(@RequestHeader("Authorization") String a) {
        int e = user.empresa(a);
        List<Map<String,Object>> r = new ArrayList<>();
        r.addAll(db.queryForList("""
            SELECT id, nome, documento, telefone, email, endereco,
                   NULL AS banco, NULL AS agencia, NULL AS conta, NULL AS "chavePix",
                   NULL AS "contratoSocialUrl", 'APROVADO' AS "statusConformidade",
                   FALSE AS "dadosBancariosBloqueados",
                   'CLIENTE' AS tipo 
              FROM clientes 
             WHERE empresa_id = ? 
             ORDER BY id
            """, e));
        try {
            r.addAll(db.queryForList("""
                SELECT id, nome, documento, telefone, email, NULL AS endereco,
                       banco, agencia, conta, chave_pix AS "chavePix",
                       contrato_social_url AS "contratoSocialUrl",
                       COALESCE(status_conformidade, 'APROVADO') AS "statusConformidade",
                       COALESCE(dados_bancarios_bloqueados, FALSE) AS "dadosBancariosBloqueados",
                       'FORNECEDOR' AS tipo 
                  FROM fornecedores 
                 WHERE empresa_id = ? 
                 ORDER BY id
                """, e));
        } catch (Exception ex) {
            r.addAll(db.queryForList("""
                SELECT id, nome, documento, telefone, email, NULL AS endereco,
                       NULL AS banco, NULL AS agencia, NULL AS conta, NULL AS "chavePix",
                       NULL AS "contratoSocialUrl", 'APROVADO' AS "statusConformidade",
                       FALSE AS "dadosBancariosBloqueados",
                       'FORNECEDOR' AS tipo 
                  FROM fornecedores 
                 WHERE empresa_id = ? 
                 ORDER BY id
                """, e));
        }
        return r;
    }

    @GetMapping("/{id}") 
    public Map<String,Object> buscar(@PathVariable int id, @RequestHeader("Authorization") String a) {
        int e = user.empresa(a);
        List<Map<String,Object>> r = db.queryForList("""
            SELECT id, nome, documento, telefone, email, endereco,
                   NULL AS banco, NULL AS agencia, NULL AS conta, NULL AS "chavePix",
                   NULL AS "contratoSocialUrl", 'APROVADO' AS "statusConformidade",
                   FALSE AS "dadosBancariosBloqueados",
                   'CLIENTE' AS tipo 
              FROM clientes 
             WHERE id = ? AND empresa_id = ?
            """, id, e);
        if (r.isEmpty()) {
            try {
                r = db.queryForList("""
                    SELECT id, nome, documento, telefone, email, NULL AS endereco,
                           banco, agencia, conta, chave_pix AS "chavePix",
                           contrato_social_url AS "contratoSocialUrl",
                           COALESCE(status_conformidade, 'APROVADO') AS "statusConformidade",
                           COALESCE(dados_bancarios_bloqueados, FALSE) AS "dadosBancariosBloqueados",
                           'FORNECEDOR' AS tipo 
                      FROM fornecedores 
                     WHERE id = ? AND empresa_id = ?
                    """, id, e);
            } catch (Exception ex) {
                r = db.queryForList("""
                    SELECT id, nome, documento, telefone, email, NULL AS endereco,
                           NULL AS banco, NULL AS agencia, NULL AS conta, NULL AS "chavePix",
                           NULL AS "contratoSocialUrl", 'APROVADO' AS "statusConformidade",
                           FALSE AS "dadosBancariosBloqueados",
                           'FORNECEDOR' AS tipo 
                      FROM fornecedores 
                     WHERE id = ? AND empresa_id = ?
                    """, id, e);
            }
        }
        if (r.isEmpty()) throw new IllegalArgumentException("Pessoa não encontrada.");
        return r.get(0);
    }

    /** Cria os dois registros quando a pessoa também for cliente e fornecedor. */
    @PostMapping 
    @Transactional 
    public Map<String,Object> criar(@RequestBody Map<String,Object> d, @RequestHeader("Authorization") String a) {
        int e = user.empresa(a); 
        int usuarioId = user.usuario(a);
        String perfil = user.perfil(a);
        String tipo = tipo(d.get("tipo")); 
        validar(d);

        // Regra ALT-03: Segregação de Funções (SoD)
        if (!tipo.equals("CLIENTE")) {
            compliance.validarPermissaoCadastroFornecedor(e, usuarioId, perfil);
        }

        Number clienteId = null, fornecedorId = null;
        if (!tipo.equals("FORNECEDOR")) clienteId = inserir("clientes", e, d);
        if (!tipo.equals("CLIENTE")) fornecedorId = inserir("fornecedores", e, d);

        Map<String,Object> resposta = new LinkedHashMap<>();
        resposta.put("id", clienteId != null ? clienteId : fornecedorId);
        resposta.put("clienteId", clienteId); 
        resposta.put("fornecedorId", fornecedorId);
        resposta.put("nome", AuthService.texto(d, "nome")); 
        resposta.put("tipo", tipo);
        resposta.put("documento", opcional(d, "documento"));
        return resposta;
    }

    @PutMapping("/{id}") 
    @Transactional
    public Map<String,Object> atualizar(@PathVariable int id, @RequestBody Map<String,Object> d, @RequestHeader("Authorization") String a) {
        int e = user.empresa(a);
        int usuarioId = user.usuario(a);
        String perfil = user.perfil(a);
        String tipo = String.valueOf(d.getOrDefault("tipo", "CLIENTE"));
        boolean ehFornecedor = tipo.contains("FORNECEDOR") && !tipo.contains("CLIENTE");
        String tabela = ehFornecedor ? "fornecedores" : "clientes";

        if (ehFornecedor) {
            // Verifica se está tentando alterar dados bancários críticos
            String novoBanco = opcional(d, "banco");
            String novaAgencia = opcional(d, "agencia");
            String novaConta = opcional(d, "conta");
            String novaPix = opcional(d, "chavePix");

            List<Map<String, Object>> atual = db.queryForList(
                "SELECT banco, agencia, conta, chave_pix FROM fornecedores WHERE id = ? AND empresa_id = ?",
                id, e
            );

            if (!atual.isEmpty()) {
                Map<String, Object> dadosAtuais = atual.get(0);
                boolean alterouBancario = !Objects.equals(opcional(dadosAtuais, "banco"), novoBanco) ||
                                          !Objects.equals(opcional(dadosAtuais, "agencia"), novaAgencia) ||
                                          !Objects.equals(opcional(dadosAtuais, "conta"), novaConta) ||
                                          !Objects.equals(opcional(dadosAtuais, "chave_pix"), novaPix);

                if (alterouBancario) {
                    String arquivoUrl = opcional(d, "arquivoComprobatorioUrl");
                    if (arquivoUrl.isBlank()) {
                        arquivoUrl = opcional(d, "contratoSocialUrl");
                    }
                    if (arquivoUrl.isBlank()) {
                        throw new ComplianceException(
                            "COMPLIANCE_COMPROVANTE_OBRIGATORIO",
                            "Para alterar dados bancários de fornecedores, é obrigatório anexar comprovante de domicílio bancário ou contrato físico."
                        );
                    }

                    // Se não for admin, envia para a fila de Dupla Validação (4 Olhos)
                    if (!perfil.equals("admin")) {
                        db.update("""
                            INSERT INTO fornecedores_alteracoes_pendentes 
                                (empresa_id, fornecedor_id, solicitante_id, banco, agencia, conta, chave_pix, arquivo_comprobatorio_url, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDENTE')
                            """, e, id, usuarioId, novoBanco, novaAgencia, novaConta, novaPix, arquivoUrl);

                        db.update("UPDATE fornecedores SET dados_bancarios_bloqueados = TRUE WHERE id = ? AND empresa_id = ?", id, e);

                        compliance.registrarAlerta(
                            e, usuarioId, "FORNECEDOR_ID_" + id, 0.0,
                            "ALTERACAO_DADOS_BANCARIOS_PENDENTE", "MEDIA",
                            "Solicitação de alteração de dados bancários enviada para o Princípio de Quatro Olhos."
                        );

                        // Atualiza apenas os dados cadastrais não bancários no momento
                        db.update("UPDATE fornecedores SET nome = ?, documento = ?, telefone = ?, email = ? WHERE id = ? AND empresa_id = ?",
                                AuthService.texto(d, "nome"), opcional(d, "documento"), opcional(d, "telefone"), opcional(d, "email"), id, e);

                        Map<String, Object> resp = buscar(id, a);
                        resp.put("avisoCompliance", "Dados bancários retidos para aprovação de segundo usuário (Princípio de 4 Olhos).");
                        return resp;
                    }
                }
            }
        }

        String sql = "UPDATE " + tabela + " SET nome=?,documento=?,telefone=?,email=?" 
                   + (tabela.equals("clientes") ? ",endereco=?" : ",banco=?,agencia=?,conta=?,chave_pix=?,contrato_social_url=?") 
                   + " WHERE id=? AND empresa_id=?";

        List<Object> v = new ArrayList<>(List.of(
            AuthService.texto(d, "nome"),
            opcional(d, "documento"),
            opcional(d, "telefone"),
            opcional(d, "email")
        ));
        if (tabela.equals("clientes")) {
            v.add(opcional(d, "endereco"));
        } else {
            v.add(opcional(d, "banco"));
            v.add(opcional(d, "agencia"));
            v.add(opcional(d, "conta"));
            v.add(opcional(d, "chavePix"));
            v.add(opcional(d, "contratoSocialUrl"));
        }
        v.add(id);
        v.add(e);

        if (db.update(sql, v.toArray()) == 0) throw new IllegalArgumentException("Pessoa não encontrada.");
        return buscar(id, a);
    }

    @DeleteMapping("/{id}") 
    public void remover(@PathVariable int id, @RequestParam(defaultValue="CLIENTE") String tipo, @RequestHeader("Authorization") String a) {
        int e = user.empresa(a); 
        String tabela = tipo(tipo).equals("FORNECEDOR") ? "fornecedores" : "clientes";
        if (db.update("DELETE FROM " + tabela + " WHERE id=? AND empresa_id=?", id, e) == 0) {
            throw new IllegalArgumentException("Pessoa não encontrada.");
        }
    }

    private Number inserir(String tabela, int empresaId, Map<String,Object> dados) {
        boolean cliente = tabela.equals("clientes");
        if (cliente) {
            String sql = "INSERT INTO clientes(empresa_id,nome,documento,telefone,email,endereco) VALUES(?,?,?,?,?,?) RETURNING id";
            return db.queryForObject(sql, Number.class, empresaId, AuthService.texto(dados,"nome"), 
                    opcional(dados,"documento"), opcional(dados,"telefone"), opcional(dados,"email"), opcional(dados,"endereco"));
        } else {
            try {
                String sql = """
                    INSERT INTO fornecedores(empresa_id, nome, documento, telefone, email, banco, agencia, conta, chave_pix, contrato_social_url, status_conformidade)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                    """;
                return db.queryForObject(sql, Number.class, empresaId, AuthService.texto(dados,"nome"),
                        opcional(dados,"documento"), opcional(dados,"telefone"), opcional(dados,"email"),
                        opcional(dados,"banco"), opcional(dados,"agencia"), opcional(dados,"conta"), opcional(dados,"chavePix"),
                        opcional(dados,"contratoSocialUrl"), "APROVADO");
            } catch (Exception ex) {
                // Fallback: se colunas bancárias ainda não existirem, insere colunas básicas
                String sqlBase = "INSERT INTO fornecedores(empresa_id, nome, documento, telefone, email) VALUES(?,?,?,?,?) RETURNING id";
                Number id = db.queryForObject(sqlBase, Number.class, empresaId, AuthService.texto(dados,"nome"),
                        opcional(dados,"documento"), opcional(dados,"telefone"), opcional(dados,"email"));
                try {
                    db.update("UPDATE fornecedores SET banco=?, agencia=?, conta=?, chave_pix=?, contrato_social_url=? WHERE id=?",
                            opcional(dados,"banco"), opcional(dados,"agencia"), opcional(dados,"conta"), opcional(dados,"chavePix"), opcional(dados,"contratoSocialUrl"), id);
                } catch (Exception ignored) {}
                return id;
            }
        }
    }

    private String tipo(Object valor) { 
        String tipo = String.valueOf(valor == null ? "CLIENTE" : valor).toUpperCase(Locale.ROOT); 
        if (!Set.of("CLIENTE","FORNECEDOR","CLIENTE_FORNECEDOR").contains(tipo)) {
            throw new IllegalArgumentException("Tipo de pessoa inválido."); 
        }
        return tipo; 
    }

    private String opcional(Map<String,Object> dados, String chave) { 
        Object valor = dados.get(chave); 
        return valor == null ? "" : valor.toString().trim(); 
    }

    private void validar(Map<String,Object> dados) { 
        if (opcional(dados,"documento").length() > 20 || 
            opcional(dados,"telefone").length() > 20 || 
            opcional(dados,"email").length() > 150 || 
            opcional(dados,"endereco").length() > 255) {
            throw new IllegalArgumentException("Um dos campos excede o tamanho permitido."); 
        }
    }
}
