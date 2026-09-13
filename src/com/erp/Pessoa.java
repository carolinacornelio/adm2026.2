package com.erp;

public class Pessoa {
    private String id;
    private int tipo; // 1 - Cliente, 2 - Fornecedor, 3 - Funcionario
    private String nome;

    public Pessoa(String id, int tipo, String nome) {
        this.id = id;
        this.tipo = tipo;
        this.nome = nome;
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

    @Override
    public String toString() {
        return id + "," + tipo + "," + nome;
    }

    public static Pessoa fromString(String str) {
        String[] parts = str.split(",");
        return new Pessoa(parts[0], Integer.parseInt(parts[1]), parts[2]);
    }
}
