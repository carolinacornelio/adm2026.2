package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Centraliza a leitura e a comparação dos planos contratados pela empresa. */
@Service
public class PlanService {
    private final JdbcTemplate db;

    public PlanService(JdbcTemplate db) {
        this.db = db;
    }

    /** Busca o plano atual; empresas antigas sem valor recebem o plano BASE. */
    public String planoDaEmpresa(int empresaId) {
        return normalizar(db.queryForObject(
                "SELECT COALESCE(plano_assinatura, 'BASE') FROM empresas WHERE id = ?",
                String.class,
                empresaId));
    }

    /** Impede o uso de um recurso quando o plano contratado é inferior. */
    public void exigir(int empresaId, String minimo) {
        String planoAtual = planoDaEmpresa(empresaId);
        if (peso(planoAtual) < peso(minimo)) {
            throw new PlanoInsuficienteException(
                    "Este recurso está disponível somente no " + nome(minimo) + ".");
        }
    }

    /** Aceita os nomes gravados pelo projeto antigo e converte para o padrão atual. */
    public static String normalizar(Object valor) {
        String plano = String.valueOf(valor == null ? "BASE" : valor)
                .trim()
                .toUpperCase(Locale.ROOT);
        return switch (plano) {
            case "PLUS", "INTERMEDIARIO", "INTERMEDIÁRIO", "EMPRESARIAL" -> "PLUS";
            case "PREMIUM", "CORPORATIVO" -> "PREMIUM";
            default -> "BASE";
        };
    }

    public static String nome(String plano) {
        return switch (normalizar(plano)) {
            case "PREMIUM" -> "Plano Premium";
            case "PLUS" -> "Plano Intermediário";
            default -> "Plano Básico";
        };
    }

    private int peso(String plano) {
        return switch (normalizar(plano)) {
            case "PREMIUM" -> 3;
            case "PLUS" -> 2;
            default -> 1;
        };
    }
}
