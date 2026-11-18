package com.erp;

/**
 * Registro inexistente OU pertencente a outra empresa (HTTP 404). A resposta é
 * a mesma nos dois casos para não revelar a existência de dados de outro tenant.
 */
public class NaoEncontradoException extends RuntimeException {
    public NaoEncontradoException(String mensagem) { super(mensagem); }
}
