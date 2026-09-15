// simulando o Spring Security no terminal. Guardará qual plano o usuário está testando e o Tenant (empresa_id)
package com.erp;

public class SessaoAtual {
    private int empresaId;
    private PlanoAssinatura plano;

    public SessaoAtual(PlanoAssinatura plano) {
        this(1, plano); // Empresa 1 por padrão
    }

    public SessaoAtual(int empresaId, PlanoAssinatura plano) {
        this.empresaId = empresaId;
        this.plano = plano;
    }

    public int getEmpresaId() {
        return empresaId;
    }

    public void setEmpresaId(int empresaId) {
        this.empresaId = empresaId;
    }

    public PlanoAssinatura getPlano() {
        return plano;
    }

    public void setPlano(PlanoAssinatura plano) {
        this.plano = plano;
    }
}