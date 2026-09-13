package com.erp;

public class Produto {
    private String id;
    private String nome;
    private double preco;
    private String categoria;
    private int quantidade;
    private int estoqueMinimo;

    public Produto(String id, String nome, double preco) {
        this.id = id;
        this.nome = nome;
        this.preco = preco;
        this.categoria = "Sem Categoria";
        this.quantidade = 0;
        this.estoqueMinimo = 0;
    }

    public String getId() {
        return id;
    }
    public String getNome() {
        return nome;
    }
    public double getPreco() {
        return preco;
    }
    
    public String getCategoria() {
        return categoria; 
    }
    public void setCategoria(String categoria) {
        this.categoria = categoria;
    }
    
    public int getQuantidade() {
        return quantidade;
    }
    public void setQuantidade(int quantidade) {
        this.quantidade = quantidade;
    }
    
    public int getEstoqueMinimo() { return estoqueMinimo; }
    public void setEstoqueMinimo(int estoqueMinimo) { this.estoqueMinimo = estoqueMinimo; }

    @Override
    public String toString() {
        return id + "," + nome + "," + preco + "," + categoria + "," + quantidade + "," + estoqueMinimo;
    }

    public static Produto fromString(String str) {
        String[] parts = str.split(",");
        Produto p = new Produto(parts[0], parts[1], Double.parseDouble(parts[2]));
        p.setCategoria(parts[3]);
        p.setQuantidade(Integer.parseInt(parts[4]));
        p.setEstoqueMinimo(Integer.parseInt(parts[5]));
        return p;
    }

}