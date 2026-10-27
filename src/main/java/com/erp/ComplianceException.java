package com.erp;

import org.springframework.http.HttpStatus;

/** Bloqueio de uma política interna de aprovação ou segregação de funções. */
public class ComplianceException extends RuntimeException {
    private final String codigo;
    private final HttpStatus status;
    public ComplianceException(String codigo, String mensagem, HttpStatus status) {
        super(mensagem); this.codigo=codigo; this.status=status;
    }
    public String codigo() { return codigo; }
    public HttpStatus status() { return status; }
}
