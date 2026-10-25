package com.erp;

/**
 * Exceção disparada quando uma regra de segurança ou conformidade da Lei Anticorrupção é violada.
 * Exemplos: violação de alçada de aprovação, quebra de segregação de funções (SoD),
 * ou inserção de despesas de alto valor/serviços sem contrato formal homologado.
 */
public class ComplianceException extends RuntimeException {
    private final String codigoErro;

    public ComplianceException(String message) {
        super(message);
        this.codigoErro = "COMPLIANCE_VIOLATION";
    }

    public ComplianceException(String codigoErro, String message) {
        super(message);
        this.codigoErro = codigoErro;
    }

    public String getCodigoErro() {
        return codigoErro;
    }
}
