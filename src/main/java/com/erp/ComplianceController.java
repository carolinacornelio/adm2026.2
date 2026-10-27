package com.erp;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Todas as identidades e empresas vêm da sessão; perfis são consultados no servidor. */
@RestController
@RequestMapping("/api")
public class ComplianceController {
    private final CurrentUser user;
    private final ComplianceService service;
    private final CompliancePurchaseService compras;
    public ComplianceController(CurrentUser user,ComplianceService service,CompliancePurchaseService compras){this.user=user;this.service=service;this.compras=compras;}
    @GetMapping("/compliance/contexto") public Map<String,Object> contexto(@RequestHeader("Authorization") String a){return service.contexto(user.usuario(a),user.perfil(a));}
    @GetMapping("/compliance/fornecedores") public List<Map<String,Object>> fornecedores(@RequestHeader("Authorization") String a){return service.fornecedores(user.empresa(a),user.perfil(a));}
    @GetMapping("/compliance/contratos") public List<Map<String,Object>> contratos(@RequestHeader("Authorization") String a){return service.contratos(user.empresa(a));}
    @PostMapping("/compliance/contratos") public Map<String,Object> criarContrato(@RequestHeader("Authorization") String a,@RequestBody Map<String,Object> d){return service.criarContrato(user.empresa(a),user.usuario(a),user.perfil(a),d);}
    @PutMapping("/compliance/contratos/{id}/homologar") public Map<String,Object> homologar(@RequestHeader("Authorization") String a,@PathVariable int id){return service.homologar(user.empresa(a),user.usuario(a),user.perfil(a),id);}
    @PostMapping("/compliance/fornecedores/{id}/dados-bancarios") public Map<String,Object> solicitarBanco(@RequestHeader("Authorization") String a,@PathVariable int id,@RequestBody Map<String,Object> d){return service.solicitarBanco(user.empresa(a),user.usuario(a),user.perfil(a),id,d);}
    @GetMapping("/compliance/alteracoes-pendentes") public List<Map<String,Object>> alteracoes(@RequestHeader("Authorization") String a){return service.alteracoes(user.empresa(a),user.usuario(a),user.perfil(a));}
    @PostMapping("/compliance/alteracoes-pendentes/{id}/aprovar") public Map<String,Object> aprovarBanco(@RequestHeader("Authorization") String a,@PathVariable int id){return service.decidirBanco(user.empresa(a),user.usuario(a),user.perfil(a),id,true);}
    @PostMapping("/compliance/alteracoes-pendentes/{id}/rejeitar") public Map<String,Object> rejeitarBanco(@RequestHeader("Authorization") String a,@PathVariable int id){return service.decidirBanco(user.empresa(a),user.usuario(a),user.perfil(a),id,false);}
    @GetMapping("/compliance/alertas") public List<Map<String,Object>> alertas(@RequestHeader("Authorization") String a){return service.alertas(user.empresa(a),user.perfil(a));}
    @PostMapping("/compliance/alertas/{id}/revisar") public Map<String,Object> revisar(@RequestHeader("Authorization") String a,@PathVariable int id,@RequestBody Map<String,Object> d){return service.revisarAlerta(user.empresa(a),user.usuario(a),user.perfil(a),id,d);}
    @GetMapping("/compliance/sod-auditoria") public List<Map<String,Object>> sod(@RequestHeader("Authorization") String a){return service.auditar(user.empresa(a),user.perfil(a));}
    @GetMapping("/compliance/usuarios") public List<Map<String,Object>> usuarios(@RequestHeader("Authorization") String a){return service.usuarios(user.empresa(a),user.perfil(a));}
    @PostMapping("/compliance/usuarios") public Map<String,Object> criarUsuario(@RequestHeader("Authorization") String a,@RequestBody Map<String,Object> d){return service.criarUsuario(user.empresa(a),user.usuario(a),user.perfil(a),d);}
    @PutMapping("/compliance/usuarios/{id}/perfil") public Map<String,Object> perfil(@RequestHeader("Authorization") String a,@PathVariable int id,@RequestBody Map<String,Object> d){return service.alterarPerfil(user.empresa(a),user.usuario(a),user.perfil(a),id,d);}
    @GetMapping("/compliance/compras/{id}") public Map<String,Object> detalhe(@RequestHeader("Authorization") String a,@PathVariable int id){return compras.detalhe(user.empresa(a),id);}
    @PostMapping("/compras/{id}/aprovar") public Map<String,Object> aprovarCompra(@RequestHeader("Authorization") String a,@PathVariable int id){return compras.aprovar(user.empresa(a),user.usuario(a),user.perfil(a),id);}
    @PostMapping("/compras/{id}/pagar") public Map<String,Object> pagar(@RequestHeader("Authorization") String a,@PathVariable int id){return compras.pagar(user.empresa(a),user.usuario(a),user.perfil(a),id);}
    @PostMapping("/compras/{id}/cancelar") public Map<String,Object> cancelar(@RequestHeader("Authorization") String a,@PathVariable int id){return compras.cancelar(user.empresa(a),user.usuario(a),user.perfil(a),id);}
}
