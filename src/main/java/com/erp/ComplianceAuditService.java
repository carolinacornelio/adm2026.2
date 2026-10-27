package com.erp;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bean separado para preservar tentativas bloqueadas mesmo quando a operação principal reverte. */
@Service
public class ComplianceAuditService {
    private final JdbcTemplate db;
    public ComplianceAuditService(JdbcTemplate db){this.db=db;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void registrarBloqueio(int empresa,int usuario,String referencia,BigDecimal valor,String regra,String nivel,String detalhes){
        registrar(empresa,usuario,referencia,valor,regra,nivel,detalhes);
    }
    public void registrar(int empresa,int usuario,String referencia,BigDecimal valor,String regra,String nivel,String detalhes){
        db.update("INSERT INTO compliance_alertas(empresa_id,usuario_id,documento_referencia,valor_transacao,motivo_alerta,nivel_severidade,detalhes) VALUES (?,?,?,?,?,?,?)",empresa,usuario,referencia,valor,regra,nivel,detalhes);
    }
}
