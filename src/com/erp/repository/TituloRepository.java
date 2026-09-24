package com.erp.repository;

import com.erp.Titulo;
import com.erp.database.DatabaseConnection;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class TituloRepository {

    public void salvar(Titulo titulo, int empresaId) throws SQLException {
        boolean ehPagar = titulo.getTipoTitulo() != null && titulo.getTipoTitulo().equalsIgnoreCase("a pagar");
        String tabela = ehPagar ? "contas_pagar" : "contas_receber";
        String colunaData = ehPagar ? "data_pagamento" : "data_recebimento";

        String sql = "INSERT INTO " + tabela + " (empresa_id, descricao, valor, vencimento, status, " + colunaData + ") "+ "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, empresaId);
            stmt.setString(2, titulo.getDescricao() != null ? titulo.getDescricao() 
                    : (ehPagar ? "Compra de mercadoria" : "Venda de mercadoria"));
            stmt.setDouble(3, titulo.getQuantidade());
            stmt.setDate(4, Date.valueOf(titulo.getData() != null ? titulo.getData() : LocalDate.now()));
            stmt.setString(5, titulo.isPago() ? "pago" : "pendente");
            if (titulo.isPago()) {
                stmt.setDate(6, Date.valueOf(titulo.getData() != null ? titulo.getData() : LocalDate.now()));
            } else {
                stmt.setNull(6, Types.DATE);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    titulo.setId(rs.getInt(1));
                }
            }
        }
    }

    // Lista todos os títulos pendentes (em aberto) da empresa informada (isolamento multi-tenant)
    public List<Titulo> listarAbertos(int empresaId) throws SQLException {
        List<Titulo> lista = new ArrayList<>();

        // Contas a Pagar em aberto
        String sqlPagar = "SELECT id, valor, vencimento, status, descricao FROM contas_pagar "+ "WHERE empresa_id = ? AND status != 'pago' ORDER BY id ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sqlPagar)) {
            stmt.setInt(1, empresaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Date venc = rs.getDate("vencimento");
                    LocalDate data = venc != null ? venc.toLocalDate() : LocalDate.now();
                    Titulo t = new Titulo(
                            rs.getInt("id"),
                            rs.getDouble("valor"),
                            false,
                            null,
                            "a pagar",
                            data,
                            rs.getString("descricao")
                    );
                    lista.add(t);
                }
            }
        }

        // Contas a Receber em aberto
        String sqlReceber = "SELECT id, valor, vencimento, status, descricao FROM contas_receber "+ "WHERE empresa_id = ? AND status != 'pago' ORDER BY id ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sqlReceber)) {
            stmt.setInt(1, empresaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Date venc = rs.getDate("vencimento");
                    LocalDate data = venc != null ? venc.toLocalDate() : LocalDate.now();
                    Titulo t = new Titulo(
                            rs.getInt("id"),
                            rs.getDouble("valor"),
                            false,
                            null,
                            "a receber",
                            data,
                            rs.getString("descricao")
                    );
                    lista.add(t);
                }
            }
        }

        return lista;
    }

    
    // Marca o título como pago com data atual
    public boolean quitarTitulo(int id, int empresaId) throws SQLException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String sqlPagar = "UPDATE contas_pagar SET status = 'pago', data_pagamento = CURRENT_DATE "+ "WHERE id = ? AND empresa_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlPagar)) {
                stmt.setInt(1, id);
                stmt.setInt(2, empresaId);
                int linhas = stmt.executeUpdate();
                if (linhas > 0) return true;
            }

            String sqlReceber = "UPDATE contas_receber SET status = 'pago', data_recebimento = CURRENT_DATE "+ "WHERE id = ? AND empresa_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlReceber)) {
                stmt.setInt(1, id);
                stmt.setInt(2, empresaId);
                int linhas = stmt.executeUpdate();
                if (linhas > 0) return true;
            }
        }
        return false;
    }

    public double obterFaturamentoPeriodo(int empresaId, LocalDate inicio, LocalDate fim) throws SQLException {
        String sql = "SELECT COALESCE(SUM(valor), 0) AS total_faturamento FROM contas_receber "+ "WHERE empresa_id = ? "+ "AND status = 'pago' "+ "AND ( "+ "  (data_recebimento IS NOT NULL AND data_recebimento BETWEEN ? AND ?) "+ "  OR (data_recebimento IS NULL AND vencimento BETWEEN ? AND ?) "+ ")";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, empresaId);
            stmt.setDate(2, Date.valueOf(inicio));
            stmt.setDate(3, Date.valueOf(fim));
            stmt.setDate(4, Date.valueOf(inicio));
            stmt.setDate(5, Date.valueOf(fim));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("total_faturamento");
                }
            }
        }
        return 0.0;
    }
}
