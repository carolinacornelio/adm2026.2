package com.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autenticação atual. A senha é armazenada com BCrypt e o token aleatório fica
 * em memória. É adequado para desenvolvimento; para produção, substituir por JWT.
 */
@Service
public class AuthService {
    private static final Set<String> PERGUNTAS_SEGURANCA = Set.of(
            "AMIGO_INFANCIA", "EMPREGO_SONHOS", "CIDADE_PAIS",
            "PRIMEIRA_ESCOLA", "ANIMAL_ESTIMACAO", "RUA_INFANCIA");
    private static final Map<String,String> ROTULOS_PERGUNTAS = Map.of(
            "AMIGO_INFANCIA", "Qual era o nome do seu melhor amigo de infância?",
            "EMPREGO_SONHOS", "Qual é o emprego dos seus sonhos?",
            "CIDADE_PAIS", "Em que cidade seus pais se conheceram?",
            "PRIMEIRA_ESCOLA", "Qual era o nome da sua primeira escola?",
            "ANIMAL_ESTIMACAO", "Qual era o nome do seu primeiro animal de estimação?",
            "RUA_INFANCIA", "Em qual rua você morou na infância?");
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Map<String,Object>> sessoes = new ConcurrentHashMap<>();
    private final Map<String, SegundoFatorPendente> segundosFatores = new ConcurrentHashMap<>();
    public AuthService(JdbcTemplate db) { this.db = db; }

    /** Cria a empresa e o primeiro usuário; a senha é salva somente como hash BCrypt. */
    @Transactional
    public Map<String,Object> cadastrar(Map<String,Object> d) {
        String email = texto(d, "email").toLowerCase();
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new IllegalArgumentException("Informe um e-mail válido.");
        String senha = texto(d, "senha");
        if (senha.length() < 6)
            throw new IllegalArgumentException("A senha deve ter pelo menos 6 caracteres.");
        if (db.queryForObject("SELECT COUNT(*) FROM usuarios WHERE email=?", Integer.class, email) > 0)
            throw new IllegalArgumentException("Já existe uma conta cadastrada com este e-mail.");
        List<String> perguntas = List.of(
                texto(d, "perguntaSeguranca1"),
                texto(d, "perguntaSeguranca2"),
                texto(d, "perguntaSeguranca3"));
        if (!perguntas.stream().allMatch(PERGUNTAS_SEGURANCA::contains))
            throw new IllegalArgumentException("Uma das perguntas de segurança é inválida.");
        if (new HashSet<>(perguntas).size() != 3)
            throw new IllegalArgumentException("Escolha três perguntas de segurança diferentes.");
        List<String> respostas = List.of(
                respostaNormalizada(d, "respostaSeguranca1"),
                respostaNormalizada(d, "respostaSeguranca2"),
                respostaNormalizada(d, "respostaSeguranca3"));
        String plano = PlanService.normalizar(d.getOrDefault("plano", "BASE"));
        String perfil = d.getOrDefault("perfil", "padrao").toString();
        Number empresaId = db.queryForObject("INSERT INTO empresas (nome_fantasia, plano_assinatura) VALUES (?, ?) RETURNING id", Number.class, texto(d,"empresa"), plano);
        Number usuarioId = db.queryForObject("""
                INSERT INTO usuarios (
                    empresa_id, nome, email, senha_hash, perfil,
                    pergunta_seguranca_1, resposta_seguranca_hash_1,
                    pergunta_seguranca_2, resposta_seguranca_hash_2,
                    pergunta_seguranca_3, resposta_seguranca_hash_3
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Number.class,
                empresaId, texto(d,"nome"), email, encoder.encode(senha), perfil,
                perguntas.get(0), encoder.encode(respostas.get(0)),
                perguntas.get(1), encoder.encode(respostas.get(1)),
                perguntas.get(2), encoder.encode(respostas.get(2)));
        return loginPorId(usuarioId.intValue(), empresaId.intValue(), texto(d,"nome"), email,
                perfil, plano);
    }
    /** Busca o usuário, compara o hash BCrypt e abre uma sessão. */
    public Map<String,Object> login(Map<String,Object> d) {
        String email = texto(d,"email").toLowerCase();
        List<Map<String,Object>> rows = db.queryForList("""
                SELECT u.id, u.empresa_id, u.nome, u.email, u.senha_hash,
                       COALESCE(u.perfil, 'padrao') AS perfil,
                       u.pergunta_seguranca_1, u.resposta_seguranca_hash_1,
                       u.pergunta_seguranca_2, u.resposta_seguranca_hash_2,
                       u.pergunta_seguranca_3, u.resposta_seguranca_hash_3,
                       COALESCE(e.plano_assinatura, 'BASE') AS plano
                  FROM usuarios u
                  JOIN empresas e ON e.id = u.empresa_id
                 WHERE u.email = ?
                """, email);
        if (rows.isEmpty() || !encoder.matches(texto(d,"senha"), (String) rows.get(0).get("senha_hash"))) throw new SecurityException("E-mail ou senha inválidos.");
        return iniciarSegundoFator(rows.get(0));
    }
    /** Confirma as perguntas de segurança antes de criar a sessão definitiva. */
    @Transactional
    public Map<String,Object> verificarSegundoFator(Map<String,Object> d) {
        String desafio = texto(d, "desafio");
        SegundoFatorPendente pendente = segundosFatores.remove(desafio);
        if (pendente == null || Instant.now().isAfter(pendente.expiraEm()))
            throw new SecurityException("A verificação expirou. Informe e-mail e senha novamente.");

        if (pendente.configuracaoNecessaria()) {
            List<String> perguntas = perguntasRecebidas(d);
            List<String> respostas = respostasRecebidas(d);
            db.update("""
                    UPDATE usuarios SET
                        pergunta_seguranca_1=?, resposta_seguranca_hash_1=?,
                        pergunta_seguranca_2=?, resposta_seguranca_hash_2=?,
                        pergunta_seguranca_3=?, resposta_seguranca_hash_3=?
                     WHERE id=?
                    """,
                    perguntas.get(0), encoder.encode(respostas.get(0)),
                    perguntas.get(1), encoder.encode(respostas.get(1)),
                    perguntas.get(2), encoder.encode(respostas.get(2)),
                    pendente.usuarioId());
        } else {
            List<String> respostas = respostasRecebidas(d);
            for (int i = 0; i < 3; i++) {
                if (!encoder.matches(respostas.get(i), pendente.respostasHash().get(i)))
                    throw new SecurityException("Uma ou mais respostas de segurança estão incorretas.");
            }
        }
        return loginPorId(pendente.usuarioId(), pendente.empresaId(), pendente.nome(),
                pendente.email(), pendente.perfil(), pendente.plano());
    }
    public Map<String,Object> usuario(String token) { Map<String,Object> u=sessoes.get(token); if(u==null) throw new SecurityException("Sessão inválida ou expirada."); return u; }
    /** Cria sessão para testes ou autenticação programática */
    public String registrarSessao(int id, int empresaId, String nome, String email, String perfil, String plano) {
        String token = UUID.randomUUID().toString();
        sessoes.put(token, Map.of(
            "id", id,
            "empresaId", empresaId,
            "nome", nome,
            "email", email,
            "perfil", perfil != null ? perfil : "padrao",
            "plano", PlanService.normalizar(plano)
        ));
        return token;
    }
    private String planoDaEmpresa(int empresaId) {
        return PlanService.normalizar(db.queryForObject(
                "SELECT COALESCE(plano_assinatura, 'BASE') FROM empresas WHERE id=?",
                String.class,
                empresaId));
    }
    private Map<String,Object> loginPorId(int id,int empresa,String nome,String email,String perfil,String plano) { 
        String token=UUID.randomUUID().toString(); 
        Map<String,Object> u=Map.of(
            "id", id,
            "empresaId", empresa,
            "nome", nome,
            "email", email,
            "perfil", perfil != null ? perfil : "padrao",
            "plano", PlanService.normalizar(plano)
        ); 
        sessoes.put(token,u); 
        return Map.of("token",token,"usuario",u); 
    }
    private Map<String,Object> iniciarSegundoFator(Map<String,Object> u) {
        List<String> perguntas = List.of(
                valorOuVazio(u.get("pergunta_seguranca_1")),
                valorOuVazio(u.get("pergunta_seguranca_2")),
                valorOuVazio(u.get("pergunta_seguranca_3")));
        List<String> hashes = List.of(
                valorOuVazio(u.get("resposta_seguranca_hash_1")),
                valorOuVazio(u.get("resposta_seguranca_hash_2")),
                valorOuVazio(u.get("resposta_seguranca_hash_3")));
        boolean configurar = perguntas.stream().anyMatch(String::isBlank)
                || hashes.stream().anyMatch(String::isBlank);
        String desafio = UUID.randomUUID().toString();
        SegundoFatorPendente pendente = new SegundoFatorPendente(
                ((Number)u.get("id")).intValue(), ((Number)u.get("empresa_id")).intValue(),
                (String)u.get("nome"), (String)u.get("email"), 
                String.valueOf(u.getOrDefault("perfil", "padrao")),
                String.valueOf(u.get("plano")),
                perguntas, hashes, configurar, Instant.now().plusSeconds(300));
        segundosFatores.put(desafio, pendente);

        Map<String,Object> resposta = new LinkedHashMap<>();
        resposta.put("segundoFatorNecessario", true);
        resposta.put("configuracaoNecessaria", configurar);
        resposta.put("desafio", desafio);
        if (!configurar) {
            resposta.put("perguntas", perguntas.stream()
                    .map(id -> Map.of("id", id, "texto", ROTULOS_PERGUNTAS.getOrDefault(id, id)))
                    .toList());
        }
        return resposta;
    }
    private List<String> perguntasRecebidas(Map<String,Object> d) {
        List<String> perguntas = List.of(texto(d,"perguntaSeguranca1"), texto(d,"perguntaSeguranca2"), texto(d,"perguntaSeguranca3"));
        if (!perguntas.stream().allMatch(PERGUNTAS_SEGURANCA::contains) || new HashSet<>(perguntas).size() != 3)
            throw new IllegalArgumentException("Escolha três perguntas de segurança válidas e diferentes.");
        return perguntas;
    }
    private List<String> respostasRecebidas(Map<String,Object> d) {
        return List.of(respostaNormalizada(d,"respostaSeguranca1"), respostaNormalizada(d,"respostaSeguranca2"), respostaNormalizada(d,"respostaSeguranca3"));
    }
    private static String valorOuVazio(Object valor) { return valor == null ? "" : valor.toString(); }
    private static String respostaNormalizada(Map<String,Object> d, String campo) {
        String resposta = Normalizer.normalize(texto(d, campo), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        if (resposta.length() < 2)
            throw new IllegalArgumentException("As respostas de segurança devem ter pelo menos 2 caracteres.");
        return resposta;
    }
    static String texto(Map<String,Object> d,String k) { Object v=d.get(k); if(v==null || v.toString().isBlank()) throw new IllegalArgumentException("Campo obrigatório: "+k); return v.toString().trim(); }
    private record SegundoFatorPendente(int usuarioId, int empresaId, String nome, String email,
                                        String perfil, String plano, List<String> perguntas, List<String> respostasHash,
                                        boolean configuracaoNecessaria, Instant expiraEm) {}
}
