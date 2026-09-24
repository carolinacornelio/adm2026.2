package com.erp;

public enum PlanoAssinatura {
    BASE(1),
    PLUS(2),
    PREMIUM(3);

    private final int peso;

    PlanoAssinatura(int peso) {
        this.peso = peso;
    }

    public int getPeso() {
        return peso;
    }
}