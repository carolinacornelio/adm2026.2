package com.erp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Leitura e validação de campos de payloads JSON. Falhas viram IllegalArgumentException (HTTP 400). */
final class Dados {
    private Dados() {}

    static String texto(Map<String, Object> d, String campo, int max, boolean obrigatorio) {
        Object v = d.get(campo);
        String t = v == null ? "" : v.toString().trim();
        if (t.isEmpty()) {
            if (obrigatorio) throw new IllegalArgumentException("Campo obrigatório: " + campo);
            return "";
        }
        if (t.length() > max) throw new IllegalArgumentException("O campo " + campo + " deve ter até " + max + " caracteres.");
        return t;
    }

    static Integer inteiro(Map<String, Object> d, String campo, boolean obrigatorio) {
        Object v = d.get(campo);
        if (v == null || v.toString().isBlank()) {
            if (obrigatorio) throw new IllegalArgumentException("Campo obrigatório: " + campo);
            return null;
        }
        try { return new BigDecimal(v.toString().trim()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("O campo " + campo + " deve ser um número inteiro."); }
    }

    /** Converte via texto para evitar erros de ponto flutuante; sempre devolve BigDecimal. */
    static BigDecimal decimal(Map<String, Object> d, String campo, boolean obrigatorio) {
        Object v = d.get(campo);
        if (v == null || v.toString().isBlank()) {
            if (obrigatorio) throw new IllegalArgumentException("Campo obrigatório: " + campo);
            return null;
        }
        try { return new BigDecimal(v.toString().trim().replace(',', '.')); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("O campo " + campo + " deve ser um número."); }
    }

    static LocalDate data(Map<String, Object> d, String campo, boolean obrigatorio) {
        Object v = d.get(campo);
        if (v == null || v.toString().isBlank()) {
            if (obrigatorio) throw new IllegalArgumentException("Campo obrigatório: " + campo);
            return null;
        }
        try { return LocalDate.parse(v.toString().trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("O campo " + campo + " deve estar no formato AAAA-MM-DD."); }
    }

    /** Lista de inteiros distintos, na ordem recebida; ausente vira lista vazia. */
    static List<Integer> inteiros(Map<String, Object> d, String campo) {
        Object v = d.get(campo);
        if (v == null) return List.of();
        if (!(v instanceof Collection<?> c)) throw new IllegalArgumentException("O campo " + campo + " deve ser uma lista.");
        LinkedHashSet<Integer> r = new LinkedHashSet<>();
        for (Object o : c) {
            try { r.add(new BigDecimal(String.valueOf(o)).intValueExact()); }
            catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("O campo " + campo + " deve conter apenas números inteiros."); }
        }
        return new ArrayList<>(r);
    }

    static List<String> textos(Map<String, Object> d, String campo) {
        Object v = d.get(campo);
        if (v == null) return List.of();
        if (!(v instanceof Collection<?> c)) throw new IllegalArgumentException("O campo " + campo + " deve ser uma lista.");
        LinkedHashSet<String> r = new LinkedHashSet<>();
        for (Object o : c) if (o != null && !o.toString().isBlank()) r.add(o.toString().trim());
        return new ArrayList<>(r);
    }

    /** Valida valor de um conjunto fechado (ex.: status). */
    static String opcao(String valor, Set<String> validos, String campo) {
        String v = valor == null ? "" : valor.trim().toUpperCase(Locale.ROOT);
        if (!validos.contains(v)) throw new IllegalArgumentException("Valor inválido para " + campo + ". Use: " + String.join(", ", new TreeSet<>(validos)) + ".");
        return v;
    }
}
