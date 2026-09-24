package com.erp;

/** Erro de autorização usado quando o recurso exige um plano superior. */
public class PlanoInsuficienteException extends RuntimeException {
    public PlanoInsuficienteException(String mensagem) {
        super(mensagem);
    }
}
