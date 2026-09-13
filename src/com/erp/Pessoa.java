package com.erp;

public class Pessoa {
    private String id;
    private int tipo; // 1 - Cliente, 2 - Fornecedor, 3 - Funcionario
    private String nome;
    private String documento;
    private String telefone;
    private String email;

    public Pessoa(String id, int tipo, String nome) {
        this.id = id;
        this.tipo = tipo;
        this.nome = nome;
        this.documento = "N/A";
        this.telefone = "N/A";
        this.email = "N/A";
    }

    public String getId() {
        return id;
    }

    public int getTipo() {
        return tipo;
    }

    public String getNome() {
        return nome;
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

    @Override
    public String toString() {
        return id + "," + tipo + "," + nome + "," + documento + "," + telefone + "," + email;
    }

    public static Pessoa fromString(String str) {
        String[] parts = str.split(",");
        Pessoa p = new Pessoa(parts[0], Integer.parseInt(parts[1]), parts[2]);
        p.setDocumento(parts[3]);
        p.setTelefone(parts[4]);
        p.setEmail(parts[5]);
        return p;
    }
}