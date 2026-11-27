package com.shopwise;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.security.Principal;
import java.time.Duration;
import java.util.*;


@RestController
@RequestMapping("/api")
class ChatGateway {
    record Turn(String message, UUID requestId, UUID confirmedQuoteId) {}
    record Reply(String text) {}

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String serviceKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ConversationLocks locks;
    private final AgentGrants grants;

    ChatGateway(JdbcTemplate db, ObjectMapper json, ConversationLocks locks, AgentGrants grants,
                @Value("${AGENT_URL:http://127.0.0.1:18082}") String agentUrl,
                @Value("${SERVICE_KEY:local-shopwise-agent}") String serviceKey) {
        this.db = db;
        this.json = json;
        this.endpoint = URI.create(agentUrl + "/turn");
        this.serviceKey = serviceKey;
        this.locks=locks;
        this.grants=grants;
    }

    private UUID thread(String buyer) {
        db.update("insert into chat_threads(id,buyer) values(?,?) on conflict(buyer) do nothing", UUID.randomUUID(), buyer);
        return db.queryForObject("select id from chat_threads where buyer=?", UUID.class, buyer);
    }


    private Map<String, Object> message(long id, String role, String payload) {
        Map<String, Object> result = json.readValue(payload, OBJECT);
        result.put("id", id);
        result.put("role", role);
        return result;
    }

    private Map<String, Object> conversation(UUID id) {
        var head = db.queryForList("select id,buyer,taken_over as \"takenOver\" from chat_threads where id=?", id);
        if (head.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        var result = head.get(0);
        result.put("messages", db.query("select id,role,payload::text from chat_messages where thread_id=? order by id",
            (row, i) -> message(row.getLong(1), row.getString(2), row.getString(3)), id));
        return result;
    }

    @GetMapping("/chat")
    Map<String, Object> own(Principal user) {
        return conversation(thread(user.getName()));
    }

    @GetMapping("/staff/chats")
    List<Map<String, Object>> inbox() {
        return db.queryForList("select id from chat_threads order by taken_over desc,buyer", UUID.class)
            .stream().map(this::conversation).toList();
    }

    @PostMapping("/staff/chats/{id}/{action:takeover|release}")
    Map<String, Object> handoff(@PathVariable UUID id, @PathVariable String action) {
        try (var guard = locks.acquire(id)) {
            db.update("update chat_threads set taken_over=? where id=?", action.equals("takeover"), id);
            return conversation(id);
        }
    }

    @PostMapping("/staff/chats/{id}/reply")
    Map<String, Object> reply(@PathVariable UUID id, @RequestBody Reply reply) {
        String text = validated(reply.text());
        try (var guard = locks.acquire(id)) {
            if (!Boolean.TRUE.equals(conversation(id).get("takenOver")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请先接管此会话");
            return append(id, "staff", null, Map.of("text", text));
        }
    }

    private String validated(String text) {
        if (text == null || text.isBlank() || text.length() > 2400)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1 至 2400 字的消息");
        return text.strip();
    }

    private Map<String, Object> append(UUID threadId, String role, UUID requestId, Map<String, Object> value) {
        String payload = json.writeValueAsString(value);
        long id = Objects.requireNonNull(db.queryForObject(
            "insert into chat_messages(thread_id,role,request_id,payload) values(?,?,?,cast(? as jsonb)) returning id",
            Long.class, threadId, role, requestId, payload));
        return message(id, role, payload);
    }

    private Optional<Map<String, Object>> existing(UUID threadId, String role, UUID requestId) {
        return db.query("select id,role,payload::text from chat_messages where thread_id=? and role=? and request_id=?",
            (r,i) -> message(r.getLong(1),r.getString(2),r.getString(3)), threadId, role, requestId).stream().findFirst();
    }

    private Map<String, Object> runAgent(UUID id, Turn turn, String text, String grant) throws IOException, InterruptedException {
        var body = new LinkedHashMap<String,Object>();
        body.put("threadId", id);
        body.put("message", text);
        body.put("requestId", turn.requestId());
        if (turn.confirmedQuoteId() != null) body.put("confirmedQuoteId", turn.confirmedQuoteId());
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(100))
            .header("X-Service-Key", serviceKey).header("Content-Type", "application/json")
            .header(AgentGrants.HEADER, grant)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Agent HTTP " + response.statusCode());
        var payload = json.readValue(response.body(), OBJECT);
        if (!(payload.get("text") instanceof String)) throw new IOException("Invalid agent response");
        return payload;
    }

    private void emit(HttpServletResponse response, String event, Object data) throws IOException {
        var writer = response.getWriter();
        writer.write("event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n");
        writer.flush();
    }

    @PostMapping(value="/chat/stream", produces="text/event-stream")
    void stream(Principal user, @RequestBody Turn turn, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String text = validated(turn.message());
        if (turn.requestId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求编号");
        String sessionId = request.getSession().getId();
        UUID id = thread(user.getName());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        emit(response, "status", Map.of("text", "正在处理本次请求…"));
        try (var guard = locks.acquire(id)) {
            var cached = existing(id, "assistant", turn.requestId());
            if (cached.isPresent()) {
                emit(response, "message", cached.get());
                return;
            }
            if (existing(id, "user", turn.requestId()).isEmpty())
                append(id, "user", turn.requestId(), Map.of("text", text));
            boolean taken = Boolean.TRUE.equals(db.queryForObject("select taken_over from chat_threads where id=?",Boolean.class,id));
            Map<String,Object> answer;
            if (taken) answer=Map.of("text","消息已送达店员。","mode","staff");
            else {
                String grant=grants.issue(user.getName(),sessionId,turn.requestId());
                try { answer=runAgent(id,turn,text,grant); }
                finally { grants.revoke(grant); }
            }
            if (Boolean.TRUE.equals(answer.get("handoff")))
                db.update("update chat_threads set taken_over=true where id=?", id);
            // Third-party offers are shown for this turn only. The transcript retains local receipts.
            var persistentAnswer = new LinkedHashMap<>(answer);
            var external = persistentAnswer.remove("external");
            var saved = append(id, "assistant", turn.requestId(), persistentAnswer);
            if (external != null) saved.put("external", external);
            emit(response, "message", saved);
        } catch (ResponseStatusException e) {
            emit(response, "error", Map.of("message", Objects.requireNonNullElse(e.getReason(), "会话暂不可用")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emit(response, "error", Map.of("message", "请求已中断，可重试本条消息。"));
        } catch (IOException e) {
            emit(response, "error", Map.of("message", "导购服务暂时无法响应，可重试或继续浏览商城。"));
        }
    }
}
