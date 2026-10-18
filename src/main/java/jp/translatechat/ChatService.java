package jp.translatechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int PAGE_SIZE = 100;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final RequestLimits limits;

    public ChatService(JdbcTemplate db, RequestLimits limits) {
        this.db = db;
        this.limits = limits;
    }

    public record JoinRequest(String displayName, String language) {}
    public record SendRequest(String clientId, String text, String sourceLanguage) {}
    public record Membership(String roomCode, UUID participantId, String displayName, String language, String token) {}
    public record Participant(UUID id, String displayName, String language) {}
    public record Message(long id, UUID participantId, String displayName, String text, String sourceLanguage,
                          String clientId, Instant createdAt) {}
    public record History(List<Message> messages, boolean hasMoreHistory) {}
    public record State(Participant self, List<Participant> participants, List<Message> messages, boolean hasMoreHistory) {}
    private record Member(UUID id, UUID roomId, String displayName, String language) {
        Participant asParticipant() { return new Participant(id, displayName, language); }
    }

    @Transactional
    public Membership createRoom(JoinRequest request) {
        validateJoin(request);
        UUID roomId = UUID.randomUUID();
        String code = randomCode();
        db.update("INSERT INTO chat_rooms(id, invite_code) VALUES (?, ?)", roomId, code);
        return addParticipant(roomId, code, request);
    }

    @Transactional
    public Membership joinRoom(String inputCode, JoinRequest request) {
        validateJoin(request);
        String code = normalizeCode(inputCode);
        List<UUID> rooms = db.query("SELECT id FROM chat_rooms WHERE invite_code=?", (r, n) -> r.getObject(1, UUID.class), code);
        if (rooms.isEmpty()) { throw failure(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"); }
        return addParticipant(rooms.getFirst(), code, request);
    }

    private Membership addParticipant(UUID roomId, String code, JoinRequest request) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID participantId = UUID.randomUUID();
        String name = request.displayName().strip();
        db.update("INSERT INTO chat_participants(id,room_id,display_name,language,token_hash) VALUES (?,?,?,?,?)",
                participantId, roomId, name, request.language(), hash(token));
        return new Membership(code, participantId, name, request.language(), token);
    }

    @Transactional
    public State state(String code, String token, long after) {
        if (after < 0) { throw failure(HttpStatus.BAD_REQUEST, "INVALID_INPUT"); }
        Member self = authenticate(code, token);
        db.update("UPDATE chat_participants SET last_seen=CURRENT_TIMESTAMP WHERE id=?", self.id());
        List<Participant> people = db.query("""
                SELECT id,display_name,language FROM chat_participants
                WHERE room_id=? AND left_at IS NULL AND last_seen > CURRENT_TIMESTAMP - INTERVAL '45 seconds'
                ORDER BY last_seen DESC, id
                """, (r, n) -> new Participant(r.getObject("id", UUID.class), r.getString("display_name"), r.getString("language")), self.roomId());
        History history = after == 0 ? history(self.roomId(), null)
                : new History(db.query(messageSelect() + " WHERE m.room_id=? AND m.id>? ORDER BY m.id ASC LIMIT ?",
                        this::mapMessage, self.roomId(), after, PAGE_SIZE), false);
        return new State(self.asParticipant(), people, history.messages(), history.hasMoreHistory());
    }

    @Transactional(readOnly = true)
    public History olderMessages(String code, String token, long before) {
        if (before <= 0) { throw failure(HttpStatus.BAD_REQUEST, "INVALID_INPUT"); }
        return history(authenticate(code, token).roomId(), before);
    }

    private History history(UUID roomId, Long before) {
        List<Message> messages = new ArrayList<>(before == null
                ? db.query(messageSelect() + " WHERE m.room_id=? ORDER BY m.id DESC LIMIT ?", this::mapMessage, roomId, PAGE_SIZE + 1)
                : db.query(messageSelect() + " WHERE m.room_id=? AND m.id<? ORDER BY m.id DESC LIMIT ?", this::mapMessage, roomId, before, PAGE_SIZE + 1));
        boolean more = messages.size() > PAGE_SIZE;
        if (more) { messages.removeLast(); }
        Collections.reverse(messages);
        return new History(messages, more);
    }

    @Transactional
    public Message send(String code, String token, SendRequest request) {
        Member self = authenticate(code, token);
        if (request == null || request.text() == null || request.text().isBlank()) { throw failure(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE"); }
        String text = request.text().strip();
        if (text.codePointCount(0, text.length()) > 2000) { throw failure(HttpStatus.BAD_REQUEST, "MESSAGE_TOO_LONG"); }
        if (!List.of("ja", "en", "und").contains(request.sourceLanguage() == null ? "" : request.sourceLanguage())) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        UUID clientId;
        try { clientId = UUID.fromString(request.clientId()); }
        catch (IllegalArgumentException | NullPointerException e) { throw failure(HttpStatus.BAD_REQUEST, "INVALID_INPUT"); }

        // Allocate IDs in commit order within a room, so polling cannot skip an uncommitted lower ID.
        // NO KEY UPDATE still allows participants to join through the room's foreign key.
        db.queryForObject("SELECT id FROM chat_rooms WHERE id=? FOR NO KEY UPDATE", UUID.class, self.roomId());

        List<Message> existing = db.query(messageSelect() + " WHERE m.room_id=? AND m.participant_id=? AND m.client_id=?",
                this::mapMessage, self.roomId(), self.id(), clientId);
        if (!existing.isEmpty()) { return sameRequest(existing.getFirst(), text, request.sourceLanguage()); }
        limits.consume("send:" + self.id(), 60, 60);
        db.update("""
                INSERT INTO chat_messages(room_id,participant_id,client_id,original_text,source_language)
                VALUES (?,?,?,?,?) ON CONFLICT (room_id,participant_id,client_id) DO NOTHING
                """, self.roomId(), self.id(), clientId, text, request.sourceLanguage());
        db.update("UPDATE chat_participants SET last_seen=CURRENT_TIMESTAMP WHERE id=?", self.id());
        Message saved = db.query(messageSelect() + " WHERE m.room_id=? AND m.participant_id=? AND m.client_id=?",
                this::mapMessage, self.roomId(), self.id(), clientId).getFirst();
        return sameRequest(saved, text, request.sourceLanguage());
    }

    private Message sameRequest(Message message, String text, String sourceLanguage) {
        if (!message.text().equals(text) || !message.sourceLanguage().equals(sourceLanguage)) {
            throw failure(HttpStatus.CONFLICT, "SEND_CONFLICT");
        }
        return message;
    }

    @Transactional
    public void leave(String code, String token) {
        Member self = authenticate(code, token);
        db.update("UPDATE chat_participants SET left_at=CURRENT_TIMESTAMP WHERE id=?", self.id());
    }

    private Member authenticate(String inputCode, String token) {
        String code = normalizeCode(inputCode);
        if (token == null || token.length() != 43) { throw failure(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED"); }
        List<Member> members = db.query("""
                SELECT p.id,p.room_id,p.display_name,p.language FROM chat_participants p
                JOIN chat_rooms r ON r.id=p.room_id
                WHERE r.invite_code=? AND p.token_hash=? AND p.left_at IS NULL
                """, (r, n) -> new Member(r.getObject("id", UUID.class), r.getObject("room_id", UUID.class),
                        r.getString("display_name"), r.getString("language")), code, hash(token));
        if (members.isEmpty()) { throw failure(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED"); }
        return members.getFirst();
    }

    private void validateJoin(JoinRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank()
                || request.displayName().strip().codePointCount(0, request.displayName().strip().length()) > 30
                || request.displayName().codePoints().anyMatch(Character::isISOControl)) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_NAME");
        }
        if (!List.of("ja", "en").contains(request.language() == null ? "" : request.language())) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_LANGUAGE");
        }
    }

    private String normalizeCode(String value) {
        if (value == null) { throw failure(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"); }
        String code = value.replace("-", "").replace(" ", "").strip().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[A-HJ-NP-Z2-9]{12}")) { throw failure(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"); }
        return code;
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(12);
        for (int i = 0; i < 12; i++) { code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()))); }
        return code.toString();
    }

    private String messageSelect() {
        return "SELECT m.*,p.display_name FROM chat_messages m JOIN chat_participants p ON p.id=m.participant_id";
    }

    private Message mapMessage(ResultSet r, int row) throws SQLException {
        return new Message(r.getLong("id"), r.getObject("participant_id", UUID.class), r.getString("display_name"),
                r.getString("original_text"), r.getString("source_language"), r.getString("client_id"), r.getTimestamp("created_at").toInstant());
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static ApiFailure failure(HttpStatus status, String code) { return new ApiFailure(status, code); }
    static class ApiFailure extends RuntimeException {
        final HttpStatus status;
        final String code;
        ApiFailure(HttpStatus status, String code) { super(code); this.status = status; this.code = code; }
    }
}
