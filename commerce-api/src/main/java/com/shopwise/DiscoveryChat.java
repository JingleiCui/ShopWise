package com.shopwise;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

@RestController
@RequestMapping("/api/v1/assistant")
class DiscoveryChat {
    record OfferRef(String provider, String variantId) {}
    record Turn(String message, List<OfferRef> references) {}
    private final URI endpoint;
    private final String key;
    private final ObjectMapper json;
    private final ConversationLocks locks;
    private final JdbcTemplate db;
    private final java.util.concurrent.Semaphore concurrent=new java.util.concurrent.Semaphore(20);
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DiscoveryChat.class);
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    DiscoveryChat(@Value("${AGENT_URL:http://127.0.0.1:18082}") String url,@Value("${SERVICE_KEY:local-shopwise-agent}") String key,
                  ObjectMapper json,ConversationLocks locks,JdbcTemplate db) {
        this.endpoint=URI.create(url+"/discovery/turn"); this.key=key; this.json=json; this.locks=locks; this.db=db;
    }

    @PostMapping(value="/turn",produces="text/event-stream")
    void turn(HttpSession session,@RequestBody Turn input,HttpServletResponse response) throws IOException {
        if (input.message()==null || input.message().isBlank() || input.message().length()>400)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入 1 至 400 字的购物需求");
        var references = input.references() == null ? List.<OfferRef>of() : input.references();
        if (references.size() > 4 || references.stream().anyMatch(ref -> ref == null
                || !("shopify".equals(ref.provider()) || "ebay".equals(ref.provider()))
                || ref.variantId() == null || ref.variantId().isBlank() || ref.variantId().length() > 220)
                || new HashSet<>(references).size() != references.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择最多 4 个不同的商品规格");
        }
        // Stable across replicas without disclosing the browser session identifier to the Agent.
        UUID thread=UUID.nameUUIDFromBytes(("discover/"+session.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!concurrent.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"导购繁忙，请稍后再试");
        String requestId=UUID.randomUUID().toString();
        long started=System.nanoTime();
        String outcome="error";
        try {
            var admitted=db.queryForList("""
                insert into discovery_quota(bucket,minute,turns) values ('all',date_trunc('minute',now()),1)
                on conflict(bucket,minute) do update set turns=discovery_quota.turns+1 where discovery_quota.turns<120
                returning turns""",Integer.class);
            if (admitted.isEmpty()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"导购繁忙，请稍后再试");
            try(var guard=locks.acquire(thread)) {
                response.setHeader("X-Request-Id",requestId);
                response.setContentType("text/event-stream;charset=UTF-8"); response.setHeader("Cache-Control","no-store");
                response.setHeader("X-Accel-Buffering","no");
                var out=response.getWriter();
                out.print("event: status\ndata: {\"text\":\"正在按中国收货条件查找商品…\"}\n\n"); out.flush();
                try {
                    var req=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(9)).header("Content-Type","application/json")
                        .header("X-Service-Key",key).header("X-Request-Id",requestId).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("threadId",thread,"message",input.message(),"references",references)))).build();
                    var res=http.send(req,HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode()!=200) throw new IOException("agent unavailable");
                    var result=json.readTree(res.body());
                    outcome=result.path("status").asString("ok");
                    out.print("event: message\ndata: "+json.writeValueAsString(result)+"\n\n");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.print("event: error\ndata: {\"message\":\"查询中断，请重试\"}\n\n");
                } catch(IOException e) {
                    out.print("event: error\ndata: {\"message\":\"导购服务暂不可用，可使用商品搜索\"}\n\n");
                }
            }
        } finally {
            concurrent.release();
            LOG.info("discovery requestId={} outcome={} durationMs={}",requestId,outcome,(System.nanoTime()-started)/1_000_000);
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    void removeOldCounters() { db.update("delete from discovery_quota where minute < now()-interval '1 day'"); }
}
