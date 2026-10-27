package com.erp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;

/** Conversões estritas compartilhadas pelos novos fluxos. Valores monetários mantêm centavos exatos. */
final class ComplianceValues {
    static final BigDecimal LIMITE=new BigDecimal("99999999.99");
    private ComplianceValues() {}
    static String texto(Map<String,Object> dados,String campo,int max,boolean obrigatorio) {
        Object valor=dados.get(campo);
        if(valor!=null && !(valor instanceof String)) throw new IllegalArgumentException("Campo de texto inválido: "+campo+".");
        String texto=valor==null?"":valor.toString().trim();
        if(texto.length()>max || (obrigatorio && texto.isBlank())) throw new IllegalArgumentException("Confira o campo "+campo+" (até "+max+" caracteres).");
        return texto;
    }
    static int inteiro(Object valor) {
        try {int numero=new BigDecimal(String.valueOf(valor)).intValueExact();if(numero<=0)throw new NumberFormatException();return numero;}
        catch(RuntimeException erro){throw new IllegalArgumentException("Informe um identificador ou quantidade inteira positiva.");}
    }
    static BigDecimal dinheiro(Object valor,boolean permiteZero) {
        try {
            BigDecimal numero=new BigDecimal(String.valueOf(valor)).setScale(2,RoundingMode.UNNECESSARY);
            if(numero.signum()<0 || (!permiteZero && numero.signum()==0) || numero.compareTo(LIMITE)>0)throw new NumberFormatException();
            return numero;
        } catch(RuntimeException erro){throw new IllegalArgumentException("Informe um valor válido, com até duas casas decimais e até R$ 99.999.999,99.");}
    }
    static LocalDate data(Object valor,LocalDate padrao) {
        if(valor==null || valor.toString().isBlank())return padrao;
        try{return LocalDate.parse(valor.toString());}catch(RuntimeException erro){throw new IllegalArgumentException("Data inválida. Use AAAA-MM-DD.");}
    }
    static String url(Map<String,Object> dados,String campo,boolean obrigatoria) {
        String texto=texto(dados,campo,500,obrigatoria);
        if(texto.isBlank())return texto;
        try {
            URI uri=URI.create(texto);
            if(!java.util.Set.of("http","https").contains(String.valueOf(uri.getScheme()).toLowerCase()) || uri.getHost()==null || uri.getUserInfo()!=null)throw new IllegalArgumentException();
            return texto;
        }catch(RuntimeException erro){throw new IllegalArgumentException("Informe um link HTTP ou HTTPS válido para o documento.");}
    }
}
