package com.erp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autenticação stateless com JWT (HS256) para suportar múltiplas instâncias
 * atrás de balanceador de carga. Senhas são armazenadas com BCrypt.
 */
@Service
public class AuthService {
    private static final long TOKEN_TTL_SECONDS = 43200L;
    private static final String JWT_HEADER = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
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
    private final ObjectMapper json = new ObjectMapper();
    private final byte[] jwtSecretBytes;
    private final Map<String, Instant> tokensRevogados = new ConcurrentHashMap<>();
    private final Map<Integer, Long> revogadoAntesDe = new ConcurrentHashMap<>();
    private final Map<String, SegundoFatorPendente> segundosFatores = new ConcurrentHashMap<>();

    @Autowired
    public AuthService(
            JdbcTemplate db,
            @Value("${erp.auth.jwt-secret:CodeScript-ERP-JWT-Secret-Key-2026-Escalabilidade}") String jwtSecret) {
        this.db = db;
        this.jwtSecretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

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
        Object docBruto = d.get("documento");
        String documento = docBruto == null || docBruto.toString().isBlank() ? null : docBruto.toString().trim();
        if (documento != null && documento.length() > 20) throw new IllegalArgumentException("O documento deve ter até 20 caracteres.");
        Number empresaId = db.queryForObject("INSERT INTO empresas (nome_fantasia, documento, plano_assinatura) VALUES (?, ?, ?) RETURNING id", Number.class, texto(d,"empresa"), documento, plano);
        Number usuarioId = db.queryForObject("""
                INSERT INTO usuarios (
                    empresa_id, nome, email, senha_hash, perfil,
                    pergunta_seguranca_1, resposta_seguranca_hash_1,
                    pergunta_seguranca_2, resposta_seguranca_hash_2,
                    pergunta_seguranca_3, resposta_seguranca_hash_3
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Number.class,
                empresaId, texto(d,"nome"), email, encoder.encode(senha), "proprietario",
                perguntas.get(0), encoder.encode(respostas.get(0)),
                perguntas.get(1), encoder.encode(respostas.get(1)),
                perguntas.get(2), encoder.encode(respostas.get(2)));
        // O responsável pelo cadastro é o PROPRIETÁRIO; os perfis iniciais nascem junto com a empresa.
        db.update("UPDATE usuarios SET proprietario = true WHERE id = ?", usuarioId);
        PerfilService.criarPerfisIniciais(db, empresaId.intValue());
        PerfilService.atribuirPerfilSistema(db, empresaId.intValue(), usuarioId.intValue(), "PROPRIETARIO");
        return loginPorId(usuarioId.intValue(), empresaId.intValue(), texto(d,"nome"), email,
                plano);
    }
    /** Busca o usuário, compara o hash BCrypt e abre uma sessão. */
    public Map<String,Object> login(Map<String,Object> d) {
        String email = texto(d,"email").toLowerCase(Locale.ROOT);
        validarTamanhoBCrypt(texto(d,"senha"),"senha");
        List<Map<String,Object>> rows = db.queryForList("""
                SELECT u.id, u.empresa_id, u.nome, u.email, u.senha_hash,
                       u.pergunta_seguranca_1, u.resposta_seguranca_hash_1,
                       u.pergunta_seguranca_2, u.resposta_seguranca_hash_2,
                       u.pergunta_seguranca_3, u.resposta_seguranca_hash_3,
                       u.ativo, e.status AS empresa_status,
                       COALESCE(e.plano_assinatura, 'BASE') AS plano
                  FROM usuarios u
                  JOIN empresas e ON e.id = u.empresa_id
                 WHERE u.email = ?
                """, email);
        if (rows.isEmpty() || !encoder.matches(texto(d,"senha"), (String) rows.get(0).get("senha_hash"))) throw new SecurityException("E-mail ou senha inválidos.");
        // Só depois de confirmar a senha: não revela se o e-mail existe.
        if (!Boolean.TRUE.equals(rows.get(0).get("ativo"))) throw new SecurityException("Conta desativada. Procure o proprietário da empresa.");
        if (!"ATIVA".equals(rows.get(0).get("empresa_status"))) throw new SecurityException("A conta da empresa está inativa.");
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
        exigirContaAtiva(pendente.usuarioId(), pendente.empresaId());
        return loginPorId(pendente.usuarioId(), pendente.empresaId(), pendente.nome(),
                pendente.email(), pendente.plano());
        }
    }
    public Map<String,Object> usuario(String token) {
        if (token == null || token.isBlank()) throw new SecurityException("Sessão inválida ou expirada.");
        Map<String, Object> claims = validarJwt(token);
        String jti = String.valueOf(claims.get("jti"));
        Instant revExp = tokensRevogados.get(jti);
        if (revExp != null) throw new SecurityException("Sessão inválida ou expirada.");
        int userId = ((Number) claims.get("id")).intValue();
        long iat = ((Number) claims.get("iat")).longValue();
        Long corte = revogadoAntesDe.get(userId);
        if (corte != null && iat <= corte) {
            throw new SecurityException("Sessão inválida ou expirada.");
        }
        return Map.of(
                "id", userId,
                "empresaId", ((Number) claims.get("empresaId")).intValue(),
                "nome", String.valueOf(claims.get("nome")),
                "email", String.valueOf(claims.get("email")),
                "plano", String.valueOf(claims.get("plano")),
                "proprietario", Boolean.TRUE.equals(claims.get("proprietario")));
    }
    public void sair(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            try {
                Map<String, Object> claims = validarJwt(token);
                String jti = String.valueOf(claims.get("jti"));
                long exp = ((Number) claims.get("exp")).longValue();
                tokensRevogados.put(jti, Instant.ofEpochSecond(exp));
            } catch (RuntimeException ignored) {
            }
        }
    }
    /** Encerra na hora todas as sessões e verificações pendentes de um usuário (desativação, troca de senha). */
    public void revogarSessoesDoUsuario(int usuarioId) {
        revogadoAntesDe.put(usuarioId, Instant.now().getEpochSecond());
        segundosFatores.entrySet().removeIf(e -> e.getValue().usuarioId() == usuarioId);
    }
    /** Troca a própria senha exigindo a atual; encerra as outras sessões do usuário. */
    @Transactional
    public void alterarSenha(int usuarioId, String atual, String nova, String tokenAtual) {
        if (atual == null || nova == null || nova.length() < 6) throw new IllegalArgumentException("A nova senha deve ter pelo menos 6 caracteres.");
        validarTamanhoBCrypt(nova, "nova senha");
        String hash = db.queryForObject("SELECT senha_hash FROM usuarios WHERE id=?", String.class, usuarioId);
        if (!encoder.matches(atual, hash)) throw new IllegalArgumentException("A senha atual está incorreta.");
        db.update("UPDATE usuarios SET senha_hash=?, updated_at=NOW() WHERE id=?", encoder.encode(nova), usuarioId);
        revogadoAntesDe.put(usuarioId, Instant.now().getEpochSecond());
    }
    private void exigirContaAtiva(int usuarioId, int empresaId) {
        Boolean ativa = db.queryForObject("SELECT u.ativo AND e.status='ATIVA' FROM usuarios u JOIN empresas e ON e.id=u.empresa_id WHERE u.id=? AND u.empresa_id=?", Boolean.class, usuarioId, empresaId);
        if (!Boolean.TRUE.equals(ativa)) throw new SecurityException("Conta desativada. Procure o proprietário da empresa.");
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=300000)
    public void limparExpirados() {
        Instant agora=Instant.now();
        tokensRevogados.entrySet().removeIf(e->!agora.isBefore(e.getValue()));
        segundosFatores.entrySet().removeIf(e->!agora.isBefore(e.getValue().expiraEm()));
    }
    private String planoDaEmpresa(int empresaId) {
        return PlanService.normalizar(db.queryForObject(
                "SELECT COALESCE(plano_assinatura, 'BASE') FROM empresas WHERE id=?",
                String.class,
                empresaId));
    }
    private Map<String,Object> loginPorId(int id,int empresa,String nome,String email,String plano) {
        boolean proprietario=Boolean.TRUE.equals(db.queryForObject("SELECT proprietario FROM usuarios WHERE id=?",Boolean.class,id));
        String planoNormalizado = PlanService.normalizar(plano);
        Map<String,Object> u=Map.of("id",id,"empresaId",empresa,"nome",nome,"email",email,"plano",planoNormalizado,"proprietario",proprietario);
        long iat = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>(u);
        claims.put("iat", iat);
        claims.put("exp", iat + TOKEN_TTL_SECONDS);
        claims.put("jti", UUID.randomUUID().toString());
        String token = gerarJwt(claims);
        return Map.of("token",token,"usuario",u);
    }

    private String gerarJwt(Map<String, Object> claims) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.writeValueAsBytes(claims));
            String conteudo = JWT_HEADER + "." + payload;
            String assinatura = assinarHmac(conteudo);
            return conteudo + "." + assinatura;
        } catch (Exception e) {
            throw new IllegalStateException("Erro ao gerar token JWT.", e);
        }
    }

    private Map<String, Object> validarJwt(String token) {
        String[] partes = token.split("\\.");
        if (partes.length != 3) throw new SecurityException("Sessão inválida ou expirada.");
        String conteudo = partes[0] + "." + partes[1];
        byte[] esperada = assinarHmacBytes(conteudo);
        byte[] recebida;
        try {
            recebida = Base64.getUrlDecoder().decode(partes[2]);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Sessão inválida ou expirada.");
        }
        if (!MessageDigest.isEqual(esperada, recebida)) {
            throw new SecurityException("Sessão inválida ou expirada.");
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(partes[1]);
            Map<String, Object> claims = json.readValue(payloadBytes, new TypeReference<>() {});
            long exp = ((Number) claims.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new SecurityException("Sessão inválida ou expirada.");
            }
            return claims;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Sessão inválida ou expirada.");
        }
    }

    private String assinarHmac(String conteudo) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(assinarHmacBytes(conteudo));
    }

    private byte[] assinarHmacBytes(String conteudo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecretBytes, "HmacSHA256"));
            return mac.doFinal(conteudo.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Erro ao assinar JWT.", e);
        }
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
                (String)u.get("nome"), (String)u.get("email"), String.valueOf(u.get("plano")),
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
                                        String plano, List<String> perguntas, List<String> respostasHash,
                                        boolean configuracaoNecessaria, Instant expiraEm,java.util.concurrent.atomic.AtomicInteger tentativas) {}
}
