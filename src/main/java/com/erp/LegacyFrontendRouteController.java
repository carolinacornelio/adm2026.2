package com.erp;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Redireciona URLs antigas que expunham a pasta front-end-erp diretamente.
 * O front-end oficial agora é servido exclusivamente por classpath:/static.
 */
@Controller
public class LegacyFrontendRouteController {

    @GetMapping({
        "/front-end-erp/categorias.html", "/cadastros/categorias.html", "/cadastros/categorias"
    })
    public String categorias() {
        return "redirect:/categorias.html?ui=20261118-modulos-v2";
    }

    @GetMapping({
        "/front-end-erp/compras.html", "/operacoes/compras.html", "/operacoes/compras"
    })
    public String compras() {
        return "redirect:/compras.html?ui=20261118-modulos-v2";
    }

    @GetMapping({
        "/front-end-erp/vendas.html", "/operacoes/vendas.html", "/operacoes/vendas"
    })
    public String vendas() {
        return "redirect:/vendas.html?ui=20261118-modulos-v2";
    }
}
