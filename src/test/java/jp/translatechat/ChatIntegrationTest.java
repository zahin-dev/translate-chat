package jp.translatechat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises real HTTP requests and PostgreSQL, using a separate test database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.url=${CHAT_TEST_DB_URL:jdbc:postgresql://127.0.0.1:55432/translate_chat_test}")
class ChatIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> request(String method, String path, JsonNode member, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (member != null) { builder.header("X-Participant-Token", member.get("token").asText()); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode node(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private JsonNode room(String name, String language) throws Exception {
        var response = request("POST", "/api/rooms", null, Map.of("displayName", name, "language", language));
        assertEquals(201, response.statusCode(), response.body());
        return node(response);
    }
    private String path(JsonNode member) { return "/api/rooms/" + member.get("roomCode").asText(); }
    private JsonNode join(JsonNode owner, String name, String language) throws Exception {
        var response = request("POST",path(owner) + "/join",null,Map.of("displayName",name,"language",language));
        assertEquals(201,response.statusCode(),response.body());
        return node(response);
    }
    private JsonNode send(JsonNode member, String text, String language) throws Exception {
        var response = request("POST",path(member) + "/messages",member,
                Map.of("clientId",UUID.randomUUID().toString(),"text",text,"sourceLanguage",language));
        assertEquals(200,response.statusCode(),response.body());
        return node(response);
    }

    @Test void groupConversationKeepsOriginalsAndPresence() throws Exception {
        var owner = room("はる","ja");
        var alex = join(owner,"Alex","en");
        join(owner,"Sam","en");
        send(owner,"今週末、一緒にご飯に行かない？","ja");
        send(alex,"Sounds good! What should we eat?","en");
        var response = request("GET",path(alex) + "/state",alex,null);
        assertEquals(200,response.statusCode());
        var state = node(response);
        assertEquals(3,state.get("participants").size());
        assertEquals("en",state.at("/self/language").asText());
        assertEquals("今週末、一緒にご飯に行かない？",state.at("/messages/0/text").asText());
        assertEquals("ja",state.at("/messages/0/sourceLanguage").asText());
        assertEquals("Sounds good! What should we eat?",state.at("/messages/1/text").asText());
        assertFalse(response.body().contains("token"));
    }

    @Test void anotherRoomAndMissingCredentialsCannotReadOrWrite() throws Exception {
        var first = room("One","ja");
        var second = room("Two","en");
        send(first,"private-room-test","en");
        assertEquals(401,request("GET",path(first) + "/state",null,null).statusCode());
        assertEquals(401,request("GET",path(first) + "/state",second,null).statusCode());
        assertEquals(401,request("POST",path(first) + "/messages",second,
                Map.of("text","wrong room","sourceLanguage","en","clientId",UUID.randomUUID().toString())).statusCode());
        assertEquals(0,node(request("GET",path(second) + "/state",second,null)).get("messages").size());
    }

    @Test void leaveRevokesTokenAndRejoinReadsSavedHistory() throws Exception {
        var first = room("Returning friend","en");
        var saved = send(first,"See you tomorrow!","en");
        assertEquals(204,request("POST",path(first) + "/leave",first,Map.of()).statusCode());
        assertEquals(401,request("GET",path(first) + "/state",first,null).statusCode());
        var returned = join(first,"Returning friend","en");
        var state = node(request("GET",path(returned) + "/state",returned,null));
        assertEquals(saved.get("id"),state.at("/messages/0/id"));
        assertEquals(1,state.get("participants").size());
        assertEquals("See you tomorrow!",db.queryForObject("SELECT original_text FROM chat_messages WHERE id=?",String.class,saved.get("id").asLong()));
    }

    @Test void retryDoesNotDuplicateMessageAndChangedPayloadIsRejected() throws Exception {
        var member = room("Retry","en");
        var payload = Map.of("text","Hello!","sourceLanguage","en","clientId",UUID.randomUUID().toString());
        var first = node(request("POST",path(member) + "/messages",member,payload));
        var retry = node(request("POST",path(member) + "/messages",member,payload));
        assertEquals(first.get("id"),retry.get("id"));
        assertEquals(1,node(request("GET",path(member) + "/state",member,null)).get("messages").size());
        var changed = Map.of("text","Changed!","sourceLanguage","en","clientId",payload.get("clientId"));
        assertEquals(409,request("POST",path(member) + "/messages",member,changed).statusCode());
    }

    @Test void invalidInputAndUnicodeLengthAreCheckedBeforeSaving() throws Exception {
        assertEquals(400,request("POST","/api/rooms",null,Map.of("displayName"," ","language","ja")).statusCode());
        assertEquals(400,request("POST","/api/rooms",null,Map.of("displayName","Name","language","fr")).statusCode());
        assertEquals(404,request("POST","/api/rooms/INVALID/join",null,Map.of("displayName","Name","language","en")).statusCode());
        var member = room("Unicode","ja");
        assertEquals(400,request("POST",path(member) + "/messages",member,
                Map.of("text","\n  ","sourceLanguage","ja","clientId",UUID.randomUUID().toString())).statusCode());
        assertEquals(400,request("POST",path(member) + "/messages",member,
                Map.of("text","あ".repeat(2001),"sourceLanguage","ja","clientId",UUID.randomUUID().toString())).statusCode());
        assertEquals(2000,send(member,"🙂".repeat(2000),"und").get("text").asText().codePointCount(0,4000));
        assertEquals(1,node(request("GET",path(member) + "/state",member,null)).get("messages").size());
    }

    @Test void oversizedRequestIsRejectedWithoutDatabaseWrite() throws Exception {
        var member = room("Body limit","en");
        assertEquals(413,request("POST",path(member) + "/messages",member,
                Map.of("text","x".repeat(20000),"sourceLanguage","en","clientId",UUID.randomUUID().toString())).statusCode());
        assertEquals(0,node(request("GET",path(member) + "/state",member,null)).get("messages").size());
    }

    @Test void pagingLoadsOlderHistoryWithoutRepeatingTheCursor() throws Exception {
        var member = room("History","en");
        UUID participant = UUID.fromString(member.get("participantId").asText());
        UUID room = db.queryForObject("SELECT room_id FROM chat_participants WHERE id=?",UUID.class,participant);
        for (int i=0;i<110;i++) {
            db.update("INSERT INTO chat_messages(room_id,participant_id,client_id,original_text,source_language) VALUES (?,?,?,?,?)",
                    room,participant,UUID.randomUUID(),"Message " + i,"en");
        }
        var latest = node(request("GET",path(member) + "/state",member,null));
        assertEquals(100,latest.get("messages").size());
        assertTrue(latest.get("hasMoreHistory").asBoolean());
        long before = latest.at("/messages/0/id").asLong();
        var earlier = node(request("GET",path(member) + "/messages?before=" + before,member,null));
        assertEquals(10,earlier.get("messages").size());
        assertFalse(earlier.get("hasMoreHistory").asBoolean());
        assertEquals("Message 0",earlier.at("/messages/0/text").asText());
        assertTrue(earlier.at("/messages/9/id").asLong() < before);
        long cursor = latest.at("/messages/99/id").asLong();
        send(member,"Only new message","en");
        var updates = node(request("GET",path(member) + "/state?after=" + cursor,member,null));
        assertEquals(1,updates.get("messages").size());
        assertEquals("Only new message",updates.at("/messages/0/text").asText());
    }

    @Test void roomCodeFormattingAndHtmlTextRemainData() throws Exception {
        var member = room("<b>Friend</b>","en");
        String code = member.get("roomCode").asText().toLowerCase();
        String formatted = code.substring(0,4) + "-" + code.substring(4,8) + "-" + code.substring(8);
        assertEquals(201,request("POST","/api/rooms/" + formatted + "/join",null,Map.of("displayName","Friend","language","ja")).statusCode());
        String text = "<img src=x onerror=alert(1)> O'Reilly\nこんにちは🙂";
        assertEquals(text,send(member,text,"ja").get("text").asText());
    }

    @Test void securityHeadersAndOriginValidationAreEnabled() throws Exception {
        var response = request("GET","/",null,null);
        assertEquals(200,response.statusCode());
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
        var forged = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/rooms"))
                .header("Origin","https://unrelated.example").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"Forged\",\"language\":\"en\"}")).build();
        assertEquals(403,client.send(forged,HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void concurrentSendsCannotCommitAYoungerMessageBeforeAnOlderOne() throws Exception {
        var member = room("Concurrent messages", "en");
        UUID participant = UUID.fromString(member.get("participantId").asText());
        UUID roomId = db.queryForObject("SELECT room_id FROM chat_participants WHERE id=?", UUID.class, participant);
        try (var transaction = db.getDataSource().getConnection();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            transaction.setAutoCommit(false);
            try {
                try (var lock = transaction.prepareStatement("SELECT id FROM chat_rooms WHERE id=? FOR NO KEY UPDATE")) {
                    lock.setObject(1, roomId);
                    lock.executeQuery().close();
                }
                try (var insert = transaction.prepareStatement("INSERT INTO chat_messages(room_id,participant_id,client_id,original_text,source_language) VALUES (?,?,?,?,?)")) {
                    insert.setObject(1, roomId);
                    insert.setObject(2, participant);
                    insert.setObject(3, UUID.randomUUID());
                    insert.setString(4, "Earlier transaction");
                    insert.setString(5, "en");
                    insert.executeUpdate();
                }
                var later = executor.submit(() -> send(member, "Later transaction", "en"));
                assertThrows(TimeoutException.class, () -> later.get(500, TimeUnit.MILLISECONDS),
                        "Later sends must wait for the earlier message to commit");
                transaction.commit();
                var savedLater = later.get(5, TimeUnit.SECONDS);
                var state = node(request("GET", path(member) + "/state", member, null));
                assertEquals(2, state.get("messages").size());
                assertEquals("Earlier transaction", state.at("/messages/0/text").asText());
                assertEquals(savedLater.get("id"), state.at("/messages/1/id"));
            } finally {
                transaction.rollback();
            }
        }
    }
}
