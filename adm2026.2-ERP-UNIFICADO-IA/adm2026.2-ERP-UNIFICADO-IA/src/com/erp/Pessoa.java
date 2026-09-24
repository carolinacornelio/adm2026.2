package com.erp;

public class Pessoa {
    private Integer id; // Gerado automaticamente pelo banco com SERIAL
    private int tipo; // 1 - Cliente, 2 - Fornecedor, 3 - Funcionario/Usuário
    private String nome;
    private String documento;
    private String telefone;
    private String email;
    private String endereco;

    public Pessoa(int tipo, String nome) {
        this(null, tipo, nome);
    }

    public Pessoa(Integer id, int tipo, String nome) {
        this.id = id;
        this.tipo = tipo;
        this.nome = nome;
        this.documento = "N/A";
        this.telefone = "N/A";
        this.email = "N/A";
        this.endereco = "";
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public int getTipo() {
        return tipo;
    }

    public void setTipo(int tipo) {
        this.tipo = tipo;
    }

    public String getTipoDescricao() {
        switch (tipo) {
            case 1: return "Cliente";
            case 2: return "Fornecedor";
            case 3: return "Funcionário";
            default: return "Outro";
        }
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getDocumento() {
        return documento;
    }

    public void setDocumento(String documento) {
        this.documento = documento;
    }

    public String getTelefone() {
        return telefone;
    }

    public void setTelefone(String telefone) {
        this.telefone = telefone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEndereco() {
        return endereco;
    }

    public void setEndereco(String endereco) {
        this.endereco = endereco;
    }

    @Override
    public String toString() {
        return (id != null ? id : "Novo") + " - " + nome + " - Tipo: " + getTipoDescricao() + " - Doc: " + documento + " - Tel: " + telefone + " - Email: " + email;
    }
}