package com.erp.repository;

import com.erp.Pessoa;
import com.erp.database.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PessoaRepository {

    public void salvar(Pessoa pessoa, int empresaId) throws SQLException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (pessoa.getId() == null) {
                // INSERT gerando o ID de forma automática com SERIAL
                if (pessoa.getTipo() == 1) {
                    // Cliente
                    String sql = "INSERT INTO clientes (empresa_id, nome, documento, telefone, email, endereco) "
                            + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, empresaId);
                        stmt.setString(2, pessoa.getNome());
                        stmt.setString(3, pessoa.getDocumento());
                        stmt.setString(4, pessoa.getTelefone());
                        stmt.setString(5, pessoa.getEmail());
                        stmt.setString(6, pessoa.getEndereco() != null ? pessoa.getEndereco() : "");
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                pessoa.setId(rs.getInt(1));
                            }
                        }
                    }
                } else if (pessoa.getTipo() == 2) {
                    // Fornecedor
                    String sql = "INSERT INTO fornecedores (empresa_id, nome, documento, telefone, email) "
                            + "VALUES (?, ?, ?, ?, ?) RETURNING id";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, empresaId);
                        stmt.setString(2, pessoa.getNome());
                        stmt.setString(3, pessoa.getDocumento());
                        stmt.setString(4, pessoa.getTelefone());
                        stmt.setString(5, pessoa.getEmail());
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                pessoa.setId(rs.getInt(1));
                            }
                        }
                    }
                } else {
                    // Funcionário / Usuário
                    String sql = "INSERT INTO usuarios (empresa_id, nome, email, senha_hash, perfil) "
                            + "VALUES (?, ?, ?, ?, ?) RETURNING id";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, empresaId);
                        stmt.setString(2, pessoa.getNome());
                        stmt.setString(3, pessoa.getEmail() != null && !pessoa.getEmail().isBlank()
                                ? pessoa.getEmail()
                                : "user" + System.currentTimeMillis() + "@erp.com");
                        stmt.setString(4, "hash_temporário");
                        stmt.setString(5, "padrão");
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                pessoa.setId(rs.getInt(1));
                            }
                        }
                    }
                }
            } else {
                // UPDATE isolado por empresa_id
                if (pessoa.getTipo() == 1) {
                    String sql = "UPDATE clientes SET nome = ?, documento = ?, telefone = ?, email = ?, endereco = ? "
                            + "WHERE id = ? AND empresa_id = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, pessoa.getNome());
                        stmt.setString(2, pessoa.getDocumento());
                        stmt.setString(3, pessoa.getTelefone());
                        stmt.setString(4, pessoa.getEmail());
                        stmt.setString(5, pessoa.getEndereco());
                        stmt.setInt(6, pessoa.getId());
                        stmt.setInt(7, empresaId);
                        stmt.executeUpdate();
                    }
                } else if (pessoa.getTipo() == 2) {
                    String sql = "UPDATE fornecedores SET nome = ?, documento = ?, telefone = ?, email = ? "
                            + "WHERE id = ? AND empresa_id = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, pessoa.getNome());
                        stmt.setString(2, pessoa.getDocumento());
                        stmt.setString(3, pessoa.getTelefone());
                        stmt.setString(4, pessoa.getEmail());
                        stmt.setInt(5, pessoa.getId());
                        stmt.setInt(6, empresaId);
                        stmt.executeUpdate();
                    }
                }
            }
        }
    }

    public Pessoa buscarPorIdETipo(int id, int tipo, int empresaId) throws SQLException {
        String tabela = (tipo == 1) ? "clientes" : ((tipo == 2) ? "fornecedores" : "usuarios");
        String sql = "SELECT * FROM " + tabela + " WHERE id = ? AND empresa_id = ?";

        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.setInt(2, empresaId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Pessoa p = new Pessoa(rs.getInt("id"), tipo, rs.getString("nome"));
                    if (tipo == 1 || tipo == 2) {
                        p.setDocumento(rs.getString("documento"));
                        p.setTelefone(rs.getString("telefone"));
                        p.setEmail(rs.getString("email"));
                        if (tipo == 1) {
                            p.setEndereco(rs.getString("endereco"));
                        }
                    } else {
                        p.setEmail(rs.getString("email"));
                    }
                    return p;
                }
            }
        }
        return null;
    }

    // Lista todas as pessoas daquela empresa (clientes, fornecedores e usuários)
    public List<Pessoa> listar(int empresaId) throws SQLException {
        List<Pessoa> lista = new ArrayList<>();

        // Clientes
        String sqlClientes = "SELECT id, nome, documento, telefone, email, endereco FROM clientes WHERE empresa_id = ? ORDER BY id";
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sqlClientes)) {
            stmt.setInt(1, empresaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Pessoa p = new Pessoa(rs.getInt("id"), 1, rs.getString("nome"));
                    p.setDocumento(rs.getString("documento"));
                    p.setTelefone(rs.getString("telefone"));
                    p.setEmail(rs.getString("email"));
                    p.setEndereco(rs.getString("endereco"));
                    lista.add(p);
                }
            }
        }

        // Fornecedores
        String sqlFornecedores = "SELECT id, nome, documento, telefone, email FROM fornecedores WHERE empresa_id = ? ORDER BY id";
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sqlFornecedores)) {
            stmt.setInt(1, empresaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Pessoa p = new Pessoa(rs.getInt("id"), 2, rs.getString("nome"));
                    p.setDocumento(rs.getString("documento"));
                    p.setTelefone(rs.getString("telefone"));
                    p.setEmail(rs.getString("email"));
                    lista.add(p);
                }
            }
        }

        // Usuários/Funcionários
        String sqlUsuarios = "SELECT id, nome, email, perfil FROM usuarios WHERE empresa_id = ? ORDER BY id";
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sqlUsuarios)) {
            stmt.setInt(1, empresaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Pessoa p = new Pessoa(rs.getInt("id"), 3, rs.getString("nome"));
                    p.setEmail(rs.getString("email"));
                    lista.add(p);
                }
            }
        }

        return lista;
    }
}
