package com.erp.repository;

import com.erp.Produto;
import com.erp.database.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ProdutoRepository {

    public void salvar(Produto produto, int empresaId) throws SQLException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Integer categoriaId = obterOuCriarCategoria(conn, produto.getCategoria(), empresaId);

                if (produto.getId() == null) {
                    // INSERT com geração de chave com SERIAL
                    String sqlProduto = "INSERT INTO produtos (empresa_id, nome, codigo, preco, categoria_id, ativo) "+ "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
                    try (PreparedStatement stmt = conn.prepareStatement(sqlProduto)) {
                        stmt.setInt(1, empresaId);
                        stmt.setString(2, produto.getNome());
                        stmt.setString(3, produto.getCodigo() != null && !produto.getCodigo().isBlank()
                                ? produto.getCodigo()
                                : "COD-" + System.currentTimeMillis());
                        stmt.setDouble(4, produto.getPreco());
                        if (categoriaId != null) {
                            stmt.setInt(5, categoriaId);
                        } else {
                            stmt.setNull(5, Types.INTEGER);
                        }
                        stmt.setBoolean(6, produto.isAtivo());

                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                int novoId = rs.getInt(1);
                                produto.setId(novoId);
                            }
                        }
                    }

                    // Inicializa o registro de estoque
                    String sqlEstoque = "INSERT INTO estoque (empresa_id, produto_id, quantidade, estoque_minimo) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement stmtEstoque = conn.prepareStatement(sqlEstoque)) {
                        stmtEstoque.setInt(1, empresaId);
                        stmtEstoque.setInt(2, produto.getId());
                        stmtEstoque.setInt(3, produto.getQuantidade());
                        stmtEstoque.setInt(4, produto.getEstoqueMinimo());
                        stmtEstoque.executeUpdate();
                    }
                } else {
                    // UPDATE isolado por empresa_id
                    String sqlUpdate = "UPDATE produtos SET nome = ?, preco = ?, categoria_id = ?, ativo = ? "
                            + "WHERE id = ? AND empresa_id = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                        stmt.setString(1, produto.getNome());
                        stmt.setDouble(2, produto.getPreco());
                        if (categoriaId != null) {
                            stmt.setInt(3, categoriaId);
                        } else {
                            stmt.setNull(3, Types.INTEGER);
                        }
                        stmt.setBoolean(4, produto.isAtivo());
                        stmt.setInt(5, produto.getId());
                        stmt.setInt(6, empresaId);
                        stmt.executeUpdate();
                    }

                    // Atualiza dados de estoque
                    String sqlEstoqueUpdate = "UPDATE estoque SET quantidade = ?, estoque_minimo = ?, updated_at = NOW() "
                            + "WHERE produto_id = ? AND empresa_id = ?";
                    try (PreparedStatement stmtEstoque = conn.prepareStatement(sqlEstoqueUpdate)) {
                        stmtEstoque.setInt(1, produto.getQuantidade());
                        stmtEstoque.setInt(2, produto.getEstoqueMinimo());
                        stmtEstoque.setInt(3, produto.getId());
                        stmtEstoque.setInt(4, empresaId);
                        int linhas = stmtEstoque.executeUpdate();
                        if (linhas == 0) {
                            String sqlEstoqueInsert = "INSERT INTO estoque (empresa_id, produto_id, quantidade, estoque_minimo) VALUES (?, ?, ?, ?)";
                            try (PreparedStatement stmtInsert = conn.prepareStatement(sqlEstoqueInsert)) {
                                stmtInsert.setInt(1, empresaId);
                                stmtInsert.setInt(2, produto.getId());
                                stmtInsert.setInt(3, produto.getQuantidade());
                                stmtInsert.setInt(4, produto.getEstoqueMinimo());
                                stmtInsert.executeUpdate();
                            }
                        }
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public Produto buscarPorId(int id, int empresaId) throws SQLException {
        String sql = "SELECT p.id, p.nome, p.codigo, p.preco, COALESCE(c.nome, 'Sem Categoria') AS categoria_nome, "+ "COALESCE(e.quantidade, 0) AS quantidade, COALESCE(e.estoque_minimo, 0) AS estoque_minimo, p.ativo "+ "FROM produtos p "+ "LEFT JOIN categorias c ON p.categoria_id = c.id AND c.empresa_id = p.empresa_id "+ "LEFT JOIN estoque e ON e.produto_id = p.id AND e.empresa_id = p.empresa_id "+ "WHERE p.id = ? AND p.empresa_id = ? AND p.ativo = TRUE";

        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            stmt.setInt(2, empresaId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Produto p = new Produto(
                            rs.getInt("id"),
                            rs.getString("nome"),
                            rs.getString("codigo"),
                            rs.getDouble("preco"),
                            rs.getString("categoria_nome"),
                            rs.getInt("quantidade"),
                            rs.getInt("estoque_minimo"));
                    p.setAtivo(rs.getBoolean("ativo"));
                    return p;
                }
            }
        }
        return null;
    }

    // Lista produtos apenas da empresa informada (multi-tenant) com filtro opcional
    // de categoria
    public List<Produto> listar(int empresaId, String filtroCategoria) throws SQLException {
        List<Produto> lista = new ArrayList<>();
        boolean temFiltro = filtroCategoria != null && !filtroCategoria.trim().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT p.id, p.nome, p.codigo, p.preco, COALESCE(c.nome, 'Sem Categoria') AS categoria_nome, "+ "COALESCE(e.quantidade, 0) AS quantidade, COALESCE(e.estoque_minimo, 0) AS estoque_minimo, p.ativo "+ "FROM produtos p "+ "LEFT JOIN categorias c ON p.categoria_id = c.id AND c.empresa_id = p.empresa_id "+ "LEFT JOIN estoque e ON e.produto_id = p.id AND e.empresa_id = p.empresa_id "+ "WHERE p.empresa_id = ? AND p.ativo = TRUE ");

        if (temFiltro) {
            sql.append("AND LOWER(c.nome) = LOWER(?) ");
        }
        sql.append("ORDER BY p.id ASC");

        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            stmt.setInt(1, empresaId);
            if (temFiltro) {
                stmt.setString(2, filtroCategoria.trim());
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Produto p = new Produto(
                            rs.getInt("id"),
                            rs.getString("nome"),
                            rs.getString("codigo"),
                            rs.getDouble("preco"),
                            rs.getString("categoria_nome"),
                            rs.getInt("quantidade"),
                            rs.getInt("estoque_minimo"));
                    p.setAtivo(rs.getBoolean("ativo"));
                    lista.add(p);
                }
            }
        }
        return lista;
    }

    public void atualizarQuantidadeEstoque(int produtoId, int novaQuantidade, int empresaId) throws SQLException {
        String sql = "UPDATE estoque SET quantidade = ?, updated_at = NOW() WHERE produto_id = ? AND empresa_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, novaQuantidade);
            stmt.setInt(2, produtoId);
            stmt.setInt(3, empresaId);
            int linhas = stmt.executeUpdate();
            if (linhas == 0) {
                String sqlInsert = "INSERT INTO estoque (empresa_id, produto_id, quantidade, estoque_minimo) VALUES (?, ?, ?, 0)";
                try (PreparedStatement stmtInsert = conn.prepareStatement(sqlInsert)) {
                    stmtInsert.setInt(1, empresaId);
                    stmtInsert.setInt(2, produtoId);
                    stmtInsert.setInt(3, novaQuantidade);
                    stmtInsert.executeUpdate();
                }
            }
        }
    }

    private Integer obterOuCriarCategoria(Connection conn, String nomeCategoria, int empresaId) throws SQLException {
        if (nomeCategoria == null || nomeCategoria.isBlank() || nomeCategoria.equalsIgnoreCase("Sem Categoria")) {
            return null;
        }

        // Buscar categoria existente para esta empresa
        String sqlBusca = "SELECT id FROM categorias WHERE empresa_id = ? AND LOWER(nome) = LOWER(?)";
        try (PreparedStatement stmt = conn.prepareStatement(sqlBusca)) {
            stmt.setInt(1, empresaId);
            stmt.setString(2, nomeCategoria.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        }

        // Criar categoria para esta empresa com SERIAL
        String sqlInsert = "INSERT INTO categorias (empresa_id, nome) VALUES (?, ?) RETURNING id";
        try (PreparedStatement stmt = conn.prepareStatement(sqlInsert)) {
            stmt.setInt(1, empresaId);
            stmt.setString(2, nomeCategoria.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        return null;
    }
}
