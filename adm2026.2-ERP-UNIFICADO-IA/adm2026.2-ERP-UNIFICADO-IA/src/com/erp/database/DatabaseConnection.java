package com.erp.database;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConnection {

    private static String url;
    private static String user;
    private static String password;

    static {
        loadConfiguration();
    }

    private static void loadConfiguration() {
        Properties props = new Properties();

        // Tenta ler do arquivo db.properties, caso ele exista
        File propFile = new File("db.properties");
        if (propFile.exists()) {
            try (InputStream input = new FileInputStream(propFile)) {
                props.load(input);
            } catch (Exception e) {
                System.err.println("Aviso: Erro ao carregar db.properties: " + e.getMessage());
            }
        }

        // Prioridade para definir a variável de ambiente 
        String envUrl = System.getenv("DATABASE_URL");
        if (envUrl == null || envUrl.isBlank()) {
            envUrl = System.getenv("NEON_DB_URL");
        }

        String rawUrl = (envUrl != null && !envUrl.isBlank()) 
                ? envUrl 
                : props.getProperty("db.url", "jdbc:postgresql://localhost:5432/neondb?sslmode=require");

        user = props.getProperty("db.user");
        password = props.getProperty("db.password");

        // Tratar e normalizar formato da URL (suporta formato URI do Neon com o usuário:senha@host)
        normalizarUrlECredenciais(rawUrl);

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("Driver JDBC do PostgreSQL não encontrado no classpath: " + e.getMessage());
        }
    }

    
    // Se a URL tiver as credenciais embutidas no formato user:password@host (o padrão do banco neon),
    // extrai o usuário e a senha e ajusta a URL para a sintaxe compatível com o driver JDBC do PostgreSQL.
    private static void normalizarUrlECredenciais(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }

        String trimmed = rawUrl.trim();

        if (trimmed.contains("@")) {
            try {
                //Remover prefixo jdbc: se existir para parsear com java.net.URI
                String uriPart = trimmed.startsWith("jdbc:") ? trimmed.substring(5) : trimmed;
                if (!uriPart.startsWith("postgresql://") && !uriPart.startsWith("postgres://")) {
                    uriPart = "postgresql://" + uriPart;
                }

                URI uri = URI.create(uriPart);
                String userInfo = uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    if (user == null || user.isBlank()) user = parts[0];
                    if (password == null || password.isBlank()) password = parts[1];
                }

                StringBuilder cleanUrl = new StringBuilder("jdbc:postgresql://");
                cleanUrl.append(uri.getHost());
                if (uri.getPort() != -1) {
                    cleanUrl.append(":").append(uri.getPort());
                }
                cleanUrl.append(uri.getPath());

                // Tratar query string
                String query = uri.getQuery();
                if (query != null && !query.isBlank()) {
                    // Remove channel_binding, se presente, para evitar problemas no handshake SSL do driver JDBC
                    query = query.replaceAll("(?i)&?channel_binding=[^&]*", "");
                    if (query.startsWith("&")) {
                        query = query.substring(1);
                    }
                    if (!query.isBlank()) {
                        cleanUrl.append("?").append(query);
                    }
                }

                // Garantir sslmode=require se não tiver especificado
                if (!cleanUrl.toString().contains("sslmode=")) {
                    cleanUrl.append(cleanUrl.toString().contains("?") ? "&" : "?").append("sslmode=require");
                }

                url = cleanUrl.toString();
                return;
            } catch (Exception e) {
                // Em caso de falha de parse por URI, usa a url original com prefixo jdbc:
            }
        }

        // Se não tem @, apenas garante o prefixo jdbc:postgresql://
        if (!trimmed.startsWith("jdbc:")) {
            if (trimmed.startsWith("postgresql://") || trimmed.startsWith("postgres://")) {
                url = "jdbc:" + trimmed;
            } else {
                url = "jdbc:postgresql://" + trimmed;
            }
        } else {
            url = trimmed;
        }

        // Remove channel_binding se estiver na query
        url = url.replaceAll("(?i)&?channel_binding=[^&]*", "");
        if (url.endsWith("?") || url.endsWith("&")) {
            url = url.substring(0, url.length() - 1);
        }
    }

    public static Connection getConnection() throws SQLException {
        if (user != null && !user.isBlank() && password != null) {
            return DriverManager.getConnection(url, user, password);
        }
        return DriverManager.getConnection(url);
    }

    public static void main(String[] args) {
        System.out.println("URL configurada: " + url);
        System.out.println("Usuário: " + user);
        System.out.println("Testando conexão com o Banco...");
        try (Connection conn = getConnection()) {
            System.out.println("SUCESSO: Conectado ao Banco (" + conn.getMetaData().getDatabaseProductName() + " versão " + conn.getMetaData().getDatabaseProductVersion() + ")!");
            System.out.println("Banco de dados ativo: " + conn.getCatalog());
        } catch (SQLException e) {
            System.err.println("FALHA na conexão: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void setCredentials(String customUrl, String customUser, String customPassword) {
        url = customUrl;
        user = customUser;
        password = customPassword;
    }

    public static String getUrl() {
        return url;
    }
}
