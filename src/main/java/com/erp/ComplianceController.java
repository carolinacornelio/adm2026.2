package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final JdbcTemplate db;
    private final CurrentUser user;
    private final ComplianceService compliance;

    public ComplianceController(JdbcTemplate db, CurrentUser user, ComplianceService compliance) {
        this.db = db;
        this.user = user;
        this.compliance = compliance;
    }

    
    // Relatório de Transações de Risco (Painel BI)
    
    @GetMapping("/alertas")
    public List<Map<String, Object>> listarAlertas(@RequestHeader("Authorization") String token) {
        return compliance.relatorioTransacoesRisco(user.empresa(token));
    }

    
    // Auditoria de Permissões e Matriz SoD
    
    @GetMapping("/sod-auditoria")
    public List<Map<String, Object>> auditarSoD(@RequestHeader("Authorization") String token) {
        return compliance.auditarPermissoesSoD(user.empresa(token));
    }

     
    // Lista contratos de fornecedores cadastrados.
     
    @GetMapping("/contratos")
    public List<Map<String, Object>> listarContratos(@RequestHeader("Authorization") String token) {
        int empresaId = user.empresa(token);
        return db.queryForList("""
            SELECT 
                c.id, c.numero_contrato AS "numeroContrato", c.objeto,
                c.valor_total AS "valorTotal", c.status, c.arquivo_url AS "arquivoUrl",
                c.data_inicio AS "dataInicio", c.data_fim AS "dataFim",
                c.created_at AS "createdAt",
                f.id AS "fornecedorId", f.nome AS "fornecedorNome"
            FROM contratos_fornecedores c
            JOIN fornecedores f ON f.id = c.fornecedor_id
            WHERE c.empresa_id = ?
            ORDER BY c.id DESC
            """, empresaId);
    }

    
    // Cadastro de contrato formal de fornecedor.
     
    @PostMapping("/contratos")
    @Transactional
    public Map<String, Object> criarContrato(@RequestBody Map<String, Object> d, @RequestHeader("Authorization") String token) {
        int empresaId = user.empresa(token);
        int usuarioId = user.usuario(token);

        int fornecedorId = ((Number) d.get("fornecedorId")).intValue();
        String numero = AuthService.texto(d, "numeroContrato");
        String objeto = d.getOrDefault("objeto", "").toString();
        double valor = ((Number) d.get("valorTotal")).doubleValue();
        String arquivoUrl = d.getOrDefault("arquivoUrl", "").toString();

        if (arquivoUrl.isBlank()) {
            throw new ComplianceException("COMPLIANCE_UPLOAD_OBRIGATORIO", "O upload/link do contrato físico assinado é obrigatório.");
        }

        // Se o contrato for superior a R$ 50k, gera alerta ALT-01
        compliance.verificarAlertaValorExcessivo(empresaId, usuarioId, "CONTRATO_" + numero, valor);

        String status = valor > 50000.0 ? "PENDENTE_COMPLIANCE" : "APROVADO";

        Number id = db.queryForObject("""
            INSERT INTO contratos_fornecedores 
                (empresa_id, fornecedor_id, numero_contrato, objeto, valor_total, status, arquivo_url, aprovador_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, Number.class, empresaId, fornecedorId, numero, objeto, valor, status, arquivoUrl, usuarioId);

        return Map.of("id", id, "status", status, "mensagem", "Contrato registrado com sucesso.");
    }

    
    // Homologação formal de contrato por compliance.
    
    @PutMapping("/contratos/{id}/homologar")
    @Transactional
    public Map<String, Object> homologarContrato(@PathVariable int id, @RequestHeader("Authorization") String token) {
        int empresaId = user.empresa(token);
        int usuarioId = user.usuario(token);
        String perfil = user.perfil(token);

        if (!perfil.equals("admin") && !perfil.equals("compliance") && !perfil.equals("diretor")) {
            throw new ComplianceException("COMPLIANCE_SEM_ALCADA", "Apenas gestores de compliance ou diretores podem homologar contratos de alto valor.");
        }

        db.update("UPDATE contratos_fornecedores SET status = 'APROVADO', aprovador_id = ? WHERE id = ? AND empresa_id = ?",
                usuarioId, id, empresaId);
        return Map.of("id", id, "status", "APROVADO", "mensagem", "Contrato formalmente homologado.");
    }

    
    // Lista alterações de dados bancários pendentes (Princípio de 4 Olhos).
    
    @GetMapping("/alteracoes-pendentes")
    public List<Map<String, Object>> listarAlteracoesPendentes(@RequestHeader("Authorization") String token) {
        int empresaId = user.empresa(token);
        return db.queryForList("""
            SELECT 
                p.id, p.banco, p.agencia, p.conta, p.chave_pix AS "chavePix",
                p.arquivo_comprobatorio_url AS "arquivoComprobatorioUrl",
                p.status, p.data_solicitacao AS "dataSolicitacao",
                f.id AS "fornecedorId", f.nome AS "fornecedorNome",
                u.nome AS "solicitanteNome", u.email AS "solicitanteEmail"
            FROM fornecedores_alteracoes_pendentes p
            JOIN fornecedores f ON f.id = p.fornecedor_id
            JOIN usuarios u ON u.id = p.solicitante_id
            WHERE p.empresa_id = ? AND p.status = 'PENDENTE'
            ORDER BY p.id DESC
            """, empresaId);
    }

    
    // Aprovação de alteração de dados bancários por segundo usuário (Princípio de 4 Olhos).
    
    @PostMapping("/alteracoes-pendentes/{id}/aprovar")
    @Transactional
    public Map<String, Object> aprovarAlteracaoBancaria(@PathVariable int id, @RequestHeader("Authorization") String token) {
        int empresaId = user.empresa(token);
        int aprovadorId = user.usuario(token);

        List<Map<String, Object>> rows = db.queryForList(
            "SELECT solicitante_id, fornecedor_id, banco, agencia, conta, chave_pix FROM fornecedores_alteracoes_pendentes WHERE id = ? AND empresa_id = ? AND status = 'PENDENTE'",
            id, empresaId
        );
        if (rows.isEmpty()) throw new IllegalArgumentException("Solicitação pendente não encontrada.");

        Map<String, Object> r = rows.get(0);
        int solicitanteId = ((Number) r.get("solicitante_id")).intValue();
        if (solicitanteId == aprovadorId) {
            throw new ComplianceException(
                "COMPLIANCE_4_OLHOS_VIOLATION",
                "Princípio dos Quatro Olhos: O mesmo usuário que solicitou a alteração bancária não pode aprová-la."
            );
        }

        int fornecedorId = ((Number) r.get("fornecedor_id")).intValue();
        db.update("""
            UPDATE fornecedores SET 
                banco = ?, agencia = ?, conta = ?, chave_pix = ?, dados_bancarios_bloqueados = FALSE
            WHERE id = ? AND empresa_id = ?
            """,
            r.get("banco"), r.get("agencia"), r.get("conta"), r.get("chave_pix"), fornecedorId, empresaId);

        db.update("UPDATE fornecedores_alteracoes_pendentes SET status = 'APROVADO', aprovador_id = ?, data_decisao = NOW() WHERE id = ?",
                aprovadorId, id);

        return Map.of("id", id, "status", "APROVADO", "mensagem", "Dados bancários validados e atualizados com sucesso.");
    }

    
    // Rejeição de alteração de dados bancários suspeita.

    @PostMapping("/alteracoes-pendentes/{id}/rejeitar")
    @Transactional
    public Map<String, Object> rejeitarAlteracaoBancaria(@PathVariable int id, @RequestHeader("Authorization") String token) {
        int empresaId = user.empresa(token);
        int aprovadorId = user.usuario(token);

        List<Map<String, Object>> rows = db.queryForList(
            "SELECT fornecedor_id FROM fornecedores_alteracoes_pendentes WHERE id = ? AND empresa_id = ?",
            id, empresaId
        );

        db.update("UPDATE fornecedores_alteracoes_pendentes SET status = 'REJEITADO', aprovador_id = ?, data_decisao = NOW() WHERE id = ? AND empresa_id = ?",
                aprovadorId, id, empresaId);

        if (!rows.isEmpty()) {
            int fornecedorId = ((Number) rows.get(0).get("fornecedor_id")).intValue();
            db.update("UPDATE fornecedores SET dados_bancarios_bloqueados = FALSE WHERE id = ? AND empresa_id = ?",
                    fornecedorId, empresaId);
        }

        return Map.of("id", id, "status", "REJEITADO", "mensagem", "Solicitação rejeitada com registro em auditoria.");
    }
}
