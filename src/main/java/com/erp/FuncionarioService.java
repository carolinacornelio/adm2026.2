package com.erp;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Subcontas: funcionários vinculados à empresa do proprietário. Nunca devolve hash de senha ou respostas de segurança. */
@Service
public class FuncionarioService {
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private final JdbcTemplate db;
    private final AuthService auth;
    private final PerfilService perfis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    public FuncionarioService(JdbcTemplate db, AuthService auth, PerfilService perfis) { this.db = db; this.auth = auth; this.perfis = perfis; }

    public List<Map<String, Object>> listar(int empresa, String status, String busca) {
        StringBuilder sql = new StringBuilder(BASE + " WHERE u.empresa_id = ?");
        List<Object> p = new ArrayList<>(List.of(empresa));
        if ("ativos".equalsIgnoreCase(status)) sql.append(" AND u.ativo");
        else if ("inativos".equalsIgnoreCase(status)) sql.append(" AND NOT u.ativo");
        if (busca != null && !busca.isBlank()) {
            sql.append(" AND (LOWER(u.nome) LIKE ? OR LOWER(u.email) LIKE ?)");
            String like = "%" + busca.trim().toLowerCase(Locale.ROOT) + "%";
            p.add(like); p.add(like);
        }
        sql.append(" ORDER BY u.proprietario DESC, LOWER(u.nome)");
        List<Map<String, Object>> lista = db.queryForList(sql.toString(), p.toArray());
        anexarVinculos(empresa, lista);
        return lista;
    }

    public Map<String, Object> buscar(int empresa, int id) {
        List<Map<String, Object>> r = db.queryForList(BASE + " WHERE u.empresa_id = ? AND u.id = ?", empresa, id);
        if (r.isEmpty()) throw new NaoEncontradoException("Funcionário não encontrado.");
        anexarVinculos(empresa, r);
        return r.get(0);
    }

    @Transactional
    public Map<String, Object> criar(Autorizacao.Contexto c, Map<String, Object> d) {
        String nome = Dados.texto(d, "nome", 150, true);
        String email = email(d);
        String senha = senha(Dados.texto(d, "senha", 200, true));
        List<Integer> perfilIds = Dados.inteiros(d, "perfis");
        if (perfilIds.isEmpty()) throw new IllegalArgumentException("Selecione ao menos um perfil de acesso.");
        validarPerfis(c, perfilIds);
        List<Integer> squadIds = Dados.inteiros(d, "squads");
        validarSquads(c.empresaId(), squadIds);
        int id;
        try {
            // proprietario NUNCA vem do payload: funcionário criado é sempre comum.
            id = db.queryForObject("INSERT INTO usuarios(empresa_id, nome, email, senha_hash, perfil, proprietario, ativo) VALUES (?,?,?,?,'funcionario',false,true) RETURNING id",
                    Integer.class, c.empresaId(), nome, email, encoder.encode(senha));
        } catch (DuplicateKeyException e) { throw new IllegalArgumentException("Já existe uma conta cadastrada com este e-mail."); }
        gravarPerfis(c.empresaId(), id, perfilIds);
        gravarSquads(c.empresaId(), id, squadIds);
        return buscar(c.empresaId(), id);
    }

    @Transactional
    public Map<String, Object> atualizar(Autorizacao.Contexto c, int id, Map<String, Object> d) {
        Map<String, Object> alvo = buscar(c.empresaId(), id);
        boolean alvoProprietario = Boolean.TRUE.equals(alvo.get("proprietario"));
        if (alvoProprietario && c.usuarioId() != id) throw new AcessoNegadoException("Somente o próprio proprietário pode alterar seus dados.");
        String nome = d.containsKey("nome") ? Dados.texto(d, "nome", 150, true) : (String) alvo.get("nome");
        String email = d.containsKey("email") ? email(d) : (String) alvo.get("email");
        try {
            db.update("UPDATE usuarios SET nome=?, email=?, updated_at=NOW() WHERE id=? AND empresa_id=?", nome, email, id, c.empresaId());
        } catch (DuplicateKeyException e) { throw new IllegalArgumentException("Já existe uma conta cadastrada com este e-mail."); }
        if (!alvoProprietario && d.containsKey("perfis")) {
            List<Integer> perfilIds = Dados.inteiros(d, "perfis");
            if (perfilIds.isEmpty()) throw new IllegalArgumentException("Selecione ao menos um perfil de acesso.");
            validarPerfis(c, perfilIds);
            if (!c.proprietario() && c.usuarioId() == id) throw new AcessoNegadoException("Você não pode alterar seus próprios perfis.");
            db.update("DELETE FROM usuario_perfis WHERE usuario_id=? AND empresa_id=?", id, c.empresaId());
            gravarPerfis(c.empresaId(), id, perfilIds);
        }
        if (d.containsKey("squads")) {
            List<Integer> squadIds = Dados.inteiros(d, "squads");
            validarSquads(c.empresaId(), squadIds);
            // Remove só os vínculos que saíram da lista; se o líder sair, a Squad fica sem líder.
            List<Integer> atuais = db.queryForList("SELECT squad_id FROM squad_membros WHERE empresa_id=? AND usuario_id=?", Integer.class, c.empresaId(), id);
            for (Integer sid : atuais) {
                if (squadIds.contains(sid)) continue;
                db.update("UPDATE squads SET lider_id=NULL, updated_at=NOW() WHERE id=? AND empresa_id=? AND lider_id=?", sid, c.empresaId(), id);
                db.update("DELETE FROM squad_membros WHERE squad_id=? AND usuario_id=? AND empresa_id=?", sid, id, c.empresaId());
            }
            gravarSquads(c.empresaId(), id, squadIds);
        }
        return buscar(c.empresaId(), id);
    }

    @Transactional
    public Map<String, Object> alterarStatus(Autorizacao.Contexto c, int id, boolean ativo) {
        Map<String, Object> alvo = buscar(c.empresaId(), id);
        if (Boolean.TRUE.equals(alvo.get("proprietario")) && !ativo) throw new IllegalArgumentException("O proprietário não pode ser desativado.");
        if (c.usuarioId() == id && !ativo) throw new IllegalArgumentException("Você não pode desativar a própria conta.");
        db.update("UPDATE usuarios SET ativo=?, updated_at=NOW() WHERE id=? AND empresa_id=?", ativo, id, c.empresaId());
        if (!ativo) {
            // Líder desativado deixa a liderança; sessões abertas caem imediatamente.
            db.update("UPDATE squads SET lider_id=NULL, updated_at=NOW() WHERE empresa_id=? AND lider_id=?", c.empresaId(), id);
            auth.revogarSessoesDoUsuario(id);
        }
        return buscar(c.empresaId(), id);
    }

    /** Define nova senha informada ou gera uma temporária (devolvida uma única vez). Encerra as sessões do funcionário. */
    @Transactional
    public Map<String, Object> redefinirSenha(Autorizacao.Contexto c, int id, Map<String, Object> d) {
        Map<String, Object> alvo = buscar(c.empresaId(), id);
        if (Boolean.TRUE.equals(alvo.get("proprietario")) && c.usuarioId() != id)
            throw new AcessoNegadoException("A senha do proprietário só pode ser alterada por ele mesmo.");
        boolean gerada = d.get("senha") == null || d.get("senha").toString().isBlank();
        String senha = gerada ? gerarSenha() : senha(d.get("senha").toString().trim());
        db.update("UPDATE usuarios SET senha_hash=?, updated_at=NOW() WHERE id=? AND empresa_id=?", encoder.encode(senha), id, c.empresaId());
        auth.revogarSessoesDoUsuario(id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("redefinida", true);
        if (gerada) r.put("senhaTemporaria", senha);
        return r;
    }

    // ---- regras internas -------------------------------------------------------------------------------

    private static final String BASE = """
            SELECT u.id, u.nome, u.email, u.ativo, u.proprietario, u.created_at AS "criadoEm"
              FROM usuarios u
            """;

    private void anexarVinculos(int empresa, List<Map<String, Object>> lista) {
        Map<Integer, List<Map<String, Object>>> perfisPorUsuario = new HashMap<>(), squadsPorUsuario = new HashMap<>();
        db.queryForList("SELECT up.usuario_id AS uid, pa.id, pa.nome FROM usuario_perfis up JOIN perfis_acesso pa ON pa.id=up.perfil_id AND pa.empresa_id=up.empresa_id WHERE up.empresa_id=? ORDER BY pa.nome", empresa)
                .forEach(l -> perfisPorUsuario.computeIfAbsent(((Number) l.remove("uid")).intValue(), k -> new ArrayList<>()).add(l));
        db.queryForList("SELECT sm.usuario_id AS uid, s.id, s.nome FROM squad_membros sm JOIN squads s ON s.id=sm.squad_id AND s.empresa_id=sm.empresa_id WHERE sm.empresa_id=? ORDER BY s.nome", empresa)
                .forEach(l -> squadsPorUsuario.computeIfAbsent(((Number) l.remove("uid")).intValue(), k -> new ArrayList<>()).add(l));
        for (Map<String, Object> u : lista) {
            int uid = ((Number) u.get("id")).intValue();
            u.put("perfis", perfisPorUsuario.getOrDefault(uid, List.of()));
            u.put("squads", squadsPorUsuario.getOrDefault(uid, List.of()));
        }
    }

    private void validarPerfis(Autorizacao.Contexto c, List<Integer> ids) {
        for (Integer pid : ids) {
            List<Map<String, Object>> r = db.queryForList("SELECT chave_sistema, ativo FROM perfis_acesso WHERE id=? AND empresa_id=?", pid, c.empresaId());
            if (r.isEmpty()) throw new IllegalArgumentException("Perfil inválido: " + pid);
            if ("PROPRIETARIO".equals(r.get(0).get("chave_sistema"))) throw new IllegalArgumentException("O perfil Proprietário não pode ser atribuído a funcionários.");
            if (!Boolean.TRUE.equals(r.get(0).get("ativo"))) throw new IllegalArgumentException("O perfil " + pid + " está desativado.");
            if (perfis.perfilExcede(c, pid)) throw new AcessoNegadoException("Você não pode atribuir um perfil com permissões que você não possui.");
        }
    }

    private void validarSquads(int empresa, List<Integer> ids) {
        for (Integer sid : ids)
            if (db.queryForObject("SELECT COUNT(*) FROM squads WHERE id=? AND empresa_id=?", Integer.class, sid, empresa) == 0)
                throw new IllegalArgumentException("Squad inválida: " + sid);
    }

    private void gravarPerfis(int empresa, int usuario, List<Integer> perfilIds) {
        for (Integer pid : perfilIds) db.update("INSERT INTO usuario_perfis(usuario_id, perfil_id, empresa_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", usuario, pid, empresa);
    }

    private void gravarSquads(int empresa, int usuario, List<Integer> squadIds) {
        for (Integer sid : squadIds) db.update("INSERT INTO squad_membros(squad_id, usuario_id, empresa_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", sid, usuario, empresa);
    }

    private String email(Map<String, Object> d) {
        String email = Dados.texto(d, "email", 150, true).toLowerCase(Locale.ROOT);
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Informe um e-mail válido.");
        return email;
    }

    private static String senha(String senha) {
        if (senha.length() < 6) throw new IllegalArgumentException("A senha deve ter pelo menos 6 caracteres.");
        if (senha.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("A senha deve ocupar no máximo 72 bytes em UTF-8.");
        return senha;
    }

    private String gerarSenha() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(ALFABETO.charAt(random.nextInt(ALFABETO.length())));
        return sb.toString();
    }
}
