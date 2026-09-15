package com.erp;

import java.time.LocalDate;

public class Titulo {
    private Integer id; // Gerado automaticamente pelo banco com SERIAL e descartando o UUID
    private double quantidade; // Valor do título
    private boolean paga;
    private Integer pessoaId;
    private String tipoTitulo; // "a pagar" ou "a receber"
    private LocalDate data;
    private String descricao;

    public Titulo(double quantidade, boolean paga, Integer pessoaId, String tipoTitulo, LocalDate data) {
        this(null, quantidade, paga, pessoaId, tipoTitulo, data, "Lançamento no sistema");
    }

    public Titulo(double quantidade, boolean paga, Integer pessoaId, String tipoTitulo, LocalDate data, String descricao) {
        this(null, quantidade, paga, pessoaId, tipoTitulo, data, descricao);
    }

    public Titulo(Integer id, double quantidade, boolean paga, Integer pessoaId, String tipoTitulo, LocalDate data) {
        this(id, quantidade, paga, pessoaId, tipoTitulo, data, "Lançamento no sistema");
    }

    public Titulo(Integer id, double quantidade, boolean paga, Integer pessoaId, String tipoTitulo, LocalDate data, String descricao) {
        this.id = id;
        this.quantidade = quantidade;
        this.paga = paga;
        this.pessoaId = pessoaId;
        this.tipoTitulo = tipoTitulo;
        this.data = data;
        this.descricao = descricao;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public double getQuantidade() {
        return quantidade;
    }

    public void setQuantidade(double quantidade) {
        this.quantidade = quantidade;
    }

    public boolean isPago() {
        return paga;
    }

    public void setPaga(boolean paga) {
        this.paga = paga;
    }

    public Integer getPessoaId() {
        return pessoaId;
    }

    public void setPessoaId(Integer pessoaId) {
        this.pessoaId = pessoaId;
    }

    public String getTipoTitulo() {
        return tipoTitulo;
    }

    public void setTipoTitulo(String tipoTitulo) {
        this.tipoTitulo = tipoTitulo;
    }

    public LocalDate getData() {
        return data;
    }

    public void setData(LocalDate data) {
        this.data = data;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    @Override
    public String toString() {
        return "Título #" + (id != null ? id : "Novo") + " - R$ " + String.format("%.2f", quantidade)+ " - Tipo: " + tipoTitulo + " - Status: " + (paga ? "PAGO" : "EM ABERTO")+ " - Pessoa ID: " + (pessoaId != null ? pessoaId : "N/A") + " - Data: " + data;
    }
}