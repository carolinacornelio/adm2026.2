package com.erp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Assinatura usada entre a fintech e o ERP: HMAC-SHA256(segredo, carimbo + "." + corpo). */
public final class FintechAssinatura {

    private FintechAssinatura() {
    }

    public static String assinar(String segredo, String carimbo, String corpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bruto = mac.doFinal((carimbo + "." + corpo).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bruto);
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível calcular a assinatura.", e);
        }
    }

    /** Comparação em tempo constante, para não vazar informação pelo tempo de resposta. */
    public static boolean confere(String segredo, String carimbo, String corpo, String recebida) {
        byte[] esperada = assinar(segredo, carimbo, corpo).getBytes(StandardCharsets.UTF_8);
        byte[] informada = recebida.toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(esperada, informada);
    }
}
