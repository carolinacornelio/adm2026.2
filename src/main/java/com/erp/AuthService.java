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
    private final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();
    private final Map<String, SegundoFatorPendente> segundosFatores = new ConcurrentHashMap<>();
    public AuthService(JdbcTemplate db) { this.db = db; }

    /** Cria a empresa e o primeiro usuário; a senha é salva somente como hash BCrypt. */
    @Transactional
    public Map<String,Object> cadastrar(Map<String,Object> d) {
        String email = texto(d, "email").toLowerCase(Locale.ROOT);
        if(email.length()>150 || texto(d,"nome").length()>150 || texto(d,"empresa").length()>150)
            throw new IllegalArgumentException("Nome, empresa e e-mail devem ter até 150 caracteres.");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new IllegalArgumentException("Informe um e-mail válido.");
        String senha = texto(d, "senha");
        if (senha.length() < 6)
            throw new IllegalArgumentException("A senha deve ter pelo menos 6 caracteres.");
        validarTamanhoBCrypt(senha,"senha");
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
        String perfil = d.getOrDefault("perfil", "padrao").toString().trim();
        if (perfil.isEmpty() || perfil.length() > 50) throw new IllegalArgumentException("Perfil inválido.");
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
        String email = texto(d,"email").toLowerCase(Locale.ROOT);
        validarTamanhoBCrypt(texto(d,"senha"),"senha");
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
        SegundoFatorPendente pendente = segundosFatores.get(desafio);
        if (pendente == null || Instant.now().isAfter(pendente.expiraEm()))
            throw new SecurityException("A verificação expirou. Informe e-mail e senha novamente.");
        synchronized(pendente) {
        if(segundosFatores.get(desafio)!=pendente || !Instant.now().isBefore(pendente.expiraEm()))
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
                if (!encoder.matches(respostas.get(i), pendente.respostasHash().get(i))) {
                    int restantes=3-pendente.tentativas().incrementAndGet();
                    if(restantes<=0)segundosFatores.remove(desafio,pendente);
                    throw new SecurityException(restantes>0?"Uma ou mais respostas estão incorretas. Restam "+restantes+" tentativa(s).":"A verificação expirou após três tentativas. Informe e-mail e senha novamente.");
                }
            }
        }
        segundosFatores.remove(desafio,pendente);
        return loginPorId(pendente.usuarioId(), pendente.empresaId(), pendente.nome(),
                pendente.email(), pendente.perfil(), pendente.plano());
        }
    }
    public Map<String,Object> usuario(String token) {
        Sessao sessao=sessoes.get(token);
        if(sessao==null || !Instant.now().isBefore(sessao.expiraEm())) {
            if(sessao!=null)sessoes.remove(token,sessao);
            throw new SecurityException("Sessão inválida ou expirada.");
        }
        return sessao.usuario();
    }
    public void sair(String authorization) {
        if(authorization!=null && authorization.startsWith("Bearer "))sessoes.remove(authorization.substring(7));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=300000)
    public void limparExpirados() {
        Instant agora=Instant.now();
        sessoes.entrySet().removeIf(e->!agora.isBefore(e.getValue().expiraEm()));
        segundosFatores.entrySet().removeIf(e->!agora.isBefore(e.getValue().expiraEm()));
    }
    /** Cria sessão para testes ou autenticação programática (compliance). */
    public String registrarSessao(int id, int empresaId, String nome, String email, String perfil, String plano) {
        String token = UUID.randomUUID().toString();
        sessoes.put(token, new Sessao(dadosSessao(id, empresaId, nome, email, perfil, plano), Instant.now().plusSeconds(43200)));
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
        Map<String,Object> u=dadosSessao(id, empresa, nome, email, perfil, plano);
        sessoes.put(token,new Sessao(u,Instant.now().plusSeconds(43200)));
        return Map.of("token",token,"usuario",u);
    }
    private static Map<String,Object> dadosSessao(int id,int empresa,String nome,String email,String perfil,String plano) {
        return Map.of(
            "id", id,
            "empresaId", empresa,
            "nome", nome,
            "email", email,
            "perfil", perfil != null ? perfil : "padrao",
            "plano", PlanService.normalizar(plano)
        );
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
                perguntas, hashes, configurar, Instant.now().plusSeconds(300),new java.util.concurrent.atomic.AtomicInteger());
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
        validarTamanhoBCrypt(resposta,"resposta de segurança");
        return resposta;
    }
    private static void validarTamanhoBCrypt(String texto,String campo) {
        if(texto.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("O campo "+campo+" deve ocupar no máximo 72 bytes em UTF-8.");
    }
    static String texto(Map<String,Object> d,String k) { Object v=d.get(k); if(v==null || v.toString().isBlank()) throw new IllegalArgumentException("Campo obrigatório: "+k); return v.toString().trim(); }
    private record SegundoFatorPendente(int usuarioId, int empresaId, String nome, String email,
                                        String perfil, String plano, List<String> perguntas, List<String> respostasHash,
                                        boolean configuracaoNecessaria, Instant expiraEm,java.util.concurrent.atomic.AtomicInteger tentativas) {}
    private record Sessao(Map<String,Object> usuario,Instant expiraEm) {}
}
