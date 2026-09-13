// simulando o Spring Security no terminal. Guardará qual plano o usuário está testando
package com.erp;

public class SessaoAtual {
    private PlanoAssinatura plano;

    public SessaoAtual(PlanoAssinatura plano) {
        this.plano = plano;
    }

    public PlanoAssinatura getPlano() {
        return plano;
    }
}