package com.erp;

public class Produto {
    private Integer id; // Gerado automaticamente pelo banco com SERIAL
    private String nome;
    private String codigo;
    private double preco;
    private String categoria;
    private int quantidade;
    private int estoqueMinimo;
    private boolean ativo;

    public Produto(String nome, double preco) {
        this(null, nome, preco);
    }

    public Produto(Integer id, String nome, double preco) {
        this.id = id;
        this.nome = nome;
        this.preco = preco;
        this.codigo = "";
        this.categoria = "Sem Categoria";
        this.quantidade = 0;
        this.estoqueMinimo = 0;
        this.ativo = true;
    }

    public Produto(Integer id, String nome, String codigo, double preco, String categoria, int quantidade, int estoqueMinimo) {
        this.id = id;
        this.nome = nome;
        this.codigo = codigo;
        this.preco = preco;
        this.categoria = categoria != null ? categoria : "Sem Categoria";
        this.quantidade = quantidade;
        this.estoqueMinimo = estoqueMinimo;
        this.ativo = true;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public double getPreco() {
        return preco;
    }

    public void setPreco(double preco) {
        this.preco = preco;
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

    public int getEstoqueMinimo() {
        return estoqueMinimo;
    }

    public void setEstoqueMinimo(int estoqueMinimo) {
        this.estoqueMinimo = estoqueMinimo;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    @Override
    public String toString() {
        return (id != null ? id : "Novo") + " - " + nome + (codigo != null && !codigo.isBlank() ? " (" + codigo + ")" : "")+ " - Cat: " + categoria + " - Qtd: " + quantidade + " - R$ " + String.format("%.2f", preco);
    }
}