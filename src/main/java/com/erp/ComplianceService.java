package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ComplianceService {

    private final JdbcTemplate db;

    public ComplianceService(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Registra um alerta ou incidente de segurança/compliance na tabela de auditoria.
     * Usa REQUIRES_NEW para persistir o incidente de segurança mesmo se a transação do negócio sofrer rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarAlerta(int empresaId, Integer usuarioId, String documentoRef, 
                                double valor, String motivo, String severidade, String detalhes) {
        try {
            db.update("""
                INSERT INTO compliance_alertas 
                    (empresa_id, usuario_id, documento_referencia, valor_transacao, motivo_alerta, nivel_severidade, detalhes, tipo_regra, motivo_risco)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                empresaId, usuarioId, documentoRef, valor, motivo, severidade, detalhes, motivo, motivo
            );
        } catch (Exception ex) {
            try {
                db.update("""
                    INSERT INTO compliance_alertas 
                        (empresa_id, usuario_id, documento_referencia, valor_transacao, motivo_alerta, nivel_severidade, detalhes)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    empresaId, usuarioId, documentoRef, valor, motivo, severidade, detalhes
                );
            } catch (Exception ex2) {
                // Fallback legado se as colunas forem motivo_risco
                db.update("""
                    INSERT INTO compliance_alertas 
                        (empresa_id, usuario_id, nivel_severidade, motivo_risco)
                    VALUES (?, ?, ?, ?)
                    """,
                    empresaId, usuarioId, severidade, motivo
                );
            }
        }
    }

    
    // Regra ALT-01: Dispara alerta automático de compliance quando uma compra ou contrato excede R$ 50.000,00.
     
    public void verificarAlertaValorExcessivo(int empresaId, int usuarioId, String docRef, double valor) {
        if (valor > 50000.0) {
            registrarAlerta(
                empresaId,
                usuarioId,
                docRef,
                valor,
                "VALOR_EXCESSIVO_CONTRATO_COMPRA",
                "CRITICA",
                "Transação com valor de R$ " + String.format(Locale.GERMANY, "%,.2f", valor) + " excede o limiar de conformidade de R$ 50.000,00."
            );
        }
    }

    
    // Regra ALT-02: Bloqueia e alerta caso uma despesa/NF de serviço seja inserida sem contrato prévio homologado.
    
    public void validarNotaFiscalServico(int empresaId, int usuarioId, double valor, Integer contratoId) {
        if (contratoId == null) {
            registrarAlerta(
                empresaId,
                usuarioId,
                "NF_SERVICO_SEM_CONTRATO",
                valor,
                "NF_SERVICO_SEM_CONTRATO",
                "ALTA",
                "Tentativa de submissão de nota fiscal de serviço sem contrato formal previamente aprovado atrelado."
            );
            throw new ComplianceException(
                "COMPLIANCE_CONTRATO_OBRIGATORIO",
                "Operação bloqueada pela Lei Anticorrupção: Toda nota fiscal de serviço exige um contrato formal previamente homologado."
            );
        }

        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, status, valor_total FROM contratos_fornecedores WHERE id = ? AND empresa_id = ?",
            contratoId, empresaId
        );
        if (rows.isEmpty()) {
            throw new ComplianceException("COMPLIANCE_CONTRATO_INEXISTENTE", "Contrato não encontrado para esta empresa.");
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if (!"APROVADO".equalsIgnoreCase(status)) {
            registrarAlerta(
                empresaId,
                usuarioId,
                "CONTRATO_ID_" + contratoId,
                valor,
                "CONTRATO_NAO_HOMOLOGADO",
                "ALTA",
                "O contrato selecionado está no status '" + status + "' e não foi homologado pelo comitê de compliance."
            );
            throw new ComplianceException(
                "COMPLIANCE_CONTRATO_PENDENTE",
                "O contrato atrelado a esta prestação de serviço encontra-se pendente ou suspenso por compliance."
            );
        }
    }

    
    // Regra ALT-03: Previne que compradores cadastrem ou homologuem fornecedores diretamente (Segregação de Funções).
    
    public void validarPermissaoCadastroFornecedor(int empresaId, int usuarioId, String perfil) {
        String p = perfil.toLowerCase();
        if (p.contains("compras") || p.contains("comprador") || p.equals("operador_compras") || p.equals("compras_operador")) {
            registrarAlerta(
                empresaId,
                usuarioId,
                "CADASTRO_FORNECEDOR_USUARIO_" + usuarioId,
                0.0,
                "QUEBRA_SOD_CADASTRO_FORNECEDOR",
                "ALTA",
                "Usuário com perfil de compras tentou cadastrar diretamente um fornecedor. Violação da política de Segregação de Funções."
            );
            throw new ComplianceException(
                "COMPLIANCE_SOD_CADASTRO_FORNECEDOR",
                "Violação de Segregação de Funções (SoD): Usuários com atribuições de compras não têm permissão para cadastrar ou homologar fornecedores."
            );
        }
    }

    
    // Regra de Ouro SoD: Quem aprova a compra NÃO pode liquidar o pagamento.
    
    public void validarRegraDeOuroSoD(int empresaId, int compraId, int usuarioLiquidanteId) {
        List<Map<String, Object>> compra = db.queryForList(
            "SELECT aprovador_nivel_1, aprovador_nivel_2, usuario_id FROM compras WHERE id = ? AND empresa_id = ?",
            compraId, empresaId
        );
        if (compra.isEmpty()) return;

        Map<String, Object> c = compra.get(0);
        Integer n1 = (Integer) c.get("aprovador_nivel_1");
        Integer n2 = (Integer) c.get("aprovador_nivel_2");
        Integer criador = (Integer) c.get("usuario_id");

        if ((n1 != null && n1 == usuarioLiquidanteId) || 
            (n2 != null && n2 == usuarioLiquidanteId) ||
            (criador != null && criador == usuarioLiquidanteId)) {
            
            registrarAlerta(
                empresaId,
                usuarioLiquidanteId,
                "COMPRA_ID_" + compraId,
                0.0,
                "VIOLACAO_REGRA_DE_OURO_SOD",
                "CRITICA",
                "Usuário tentou liquidar pagamento de uma compra que ele próprio criou ou aprovou."
            );
            throw new ComplianceException(
                "COMPLIANCE_SOD_REGRA_DE_OURO",
                "Violação da Regra de Ouro SoD: O usuário que solicitou ou aprovou a compra não pode liquidar o pagamento."
            );
        }
    }

    
    // Relatório de Transações de Risco (BI)
    public List<Map<String, Object>> relatorioTransacoesRisco(int empresaId) {
        return db.queryForList("""
            SELECT 
                a.id,
                a.documento_referencia AS "documentoReferencia",
                a.valor_transacao AS "valorTransacao",
                a.motivo_alerta AS "motivoAlerta",
                a.nivel_severidade AS "nivelSeveridade",
                a.detalhes,
                a.status,
                a.data_hora AS "dataHora",
                u.id AS "usuarioId",
                COALESCE(u.nome, 'Sistema') AS "usuarioNome",
                COALESCE(u.email, '-') AS "usuarioEmail",
                COALESCE(u.perfil, 'padrao') AS "usuarioPerfil"
            FROM compliance_alertas a
            LEFT JOIN usuarios u ON u.id = a.usuario_id
            WHERE a.empresa_id = ?
            ORDER BY a.data_hora DESC, a.id DESC
            """, empresaId);
    }

    
    // Auditoria de Permissões e Matriz SoD
    
    public List<Map<String, Object>> auditarPermissoesSoD(int empresaId) {
        List<Map<String, Object>> usuarios = db.queryForList(
            "SELECT id, nome, email, COALESCE(perfil, 'padrao') AS perfil FROM usuarios WHERE empresa_id = ? ORDER BY id",
            empresaId
        );

        List<Map<String, Object>> auditoria = new ArrayList<>();
        for (Map<String, Object> u : usuarios) {
            String perfil = String.valueOf(u.get("perfil")).toLowerCase();
            int id = ((Number) u.get("id")).intValue();
            String nome = (String) u.get("nome");
            String email = (String) u.get("email");

            if (perfil.equals("admin") || perfil.equals("administrador")) {
                auditoria.add(Map.of(
                    "usuarioId", id,
                    "nome", nome,
                    "email", email,
                    "perfil", perfil,
                    "tipoRisco", "PRIVILEGIO_EXCESSIVO",
                    "severidade", "ALTA",
                    "diagnostico", "Usuário com perfil de Administrador Global. Acesso irrestrito a compras e tesouraria deve ser revogado ou monitorado.",
                    "acaoRecomendada", "Migrar para perfis específicos segregados."
                ));
            } else if (perfil.contains("compras") && perfil.contains("financeiro")) {
                auditoria.add(Map.of(
                    "usuarioId", id,
                    "nome", nome,
                    "email", email,
                    "perfil", perfil,
                    "tipoRisco", "CONFLITO_SOD_CRITICO",
                    "severidade", "CRITICA",
                    "diagnostico", "Conflito direto entre alçada de compras e liquidação financeira.",
                    "acaoRecomendada", "Segregação imediata de papéis."
                ));
            } else {
                auditoria.add(Map.of(
                    "usuarioId", id,
                    "nome", nome,
                    "email", email,
                    "perfil", perfil,
                    "tipoRisco", "CONFORME",
                    "severidade", "BAIXA",
                    "diagnostico", "Perfil devidamente segregado.",
                    "acaoRecomendada", "Manter revisão periódica."
                ));
            }
        }
        return auditoria;
    }
}
