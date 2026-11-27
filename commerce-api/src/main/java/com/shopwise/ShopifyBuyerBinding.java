package com.shopwise;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

@RestController
@RequestMapping("/api/v1/merchant/binding")
class ShopifyBuyerBinding {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String shop,clientId,redirect;
    private final URI authorize,token,graphql;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final SecureRandom random=new SecureRandom();

    ShopifyBuyerBinding(JdbcTemplate db,ObjectMapper json,
            @Value("${SHOPIFY_PILOT_SHOP:}") String shop,@Value("${SHOPIFY_CUSTOMER_CLIENT_ID:}") String clientId,
            @Value("${SHOPIFY_CUSTOMER_AUTHORIZE_URL:}") String authorize,@Value("${SHOPIFY_CUSTOMER_TOKEN_URL:}") String token,
            @Value("${SHOPIFY_CUSTOMER_GRAPHQL_URL:}") String graphql,@Value("${SHOPIFY_CUSTOMER_REDIRECT_URL:}") String redirect) {
        this.db=db; this.json=json; this.shop=shop; this.clientId=clientId; this.redirect=redirect;
        this.authorize=clientId.isBlank()?null:https(authorize);
        this.token=clientId.isBlank()?null:https(token);
        this.graphql=clientId.isBlank()?null:https(graphql);
        if (!clientId.isBlank() && (shop.isBlank() || !https(redirect).getPath().equals("/api/v1/merchant/binding/callback")))
            throw new IllegalArgumentException("Configure pilot shop and registered callback URL");
    }

    @GetMapping
    Map<String,Boolean> status(java.security.Principal buyer,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        boolean bound=!db.queryForList("select 1 from merchant_binding where shop=? and buyer=?",Integer.class,shop,buyer.getName()).isEmpty();
        return Map.of("enabled",!clientId.isBlank(),"bound",bound);
    }

    @PostMapping("/start")
    Map<String,String> start(java.security.Principal buyer,HttpSession session,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if (authorize==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"店铺身份绑定尚未开放");
        String state=secret(), verifier=secret();
        db.update("""
            insert into merchant_binding_flow(state,buyer,session_id,verifier,expires_at)
            values (?,?,?,?,now()+interval '8 minutes')
            """,state,buyer.getName(),session.getId(),verifier);
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        return Map.of("authorizationUrl",authorize+"?"+form(Map.of("client_id",clientId,"response_type","code",
            "redirect_uri",redirect,"state",state,"scope","openid email customer-account-api:full",
            "code_challenge_method","S256","code_challenge",challenge,"nonce",secret())));
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam String state,@RequestParam String code,java.security.Principal buyer,
                                  HttpSession session) throws IOException,InterruptedException {
        if (token==null || !state.matches("[A-Za-z0-9_-]{43}") || code.length()>4096)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"身份绑定请求已失效");
        var existing=db.queryForList("""
            delete from merchant_binding_flow where state=? and buyer=? and session_id=? and expires_at>now()
            returning verifier
            """,String.class,state,buyer.getName(),session.getId());
        if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"身份绑定请求已失效");
        var issued=send(token,"application/x-www-form-urlencoded",form(Map.of("client_id",clientId,"grant_type","authorization_code",
            "redirect_uri",redirect,"code",code,"code_verifier",existing.get(0))),null);
        String access=issued.path("access_token").asString("");
        if (access.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"商家身份校验失败");
        var profile=send(graphql,"application/json",json.writeValueAsString(Map.of("query","query { customer { id } }")),access);
        String gid=profile.path("data").path("customer").path("id").asString("");
        if (!gid.matches("gid://shopify/Customer/[0-9]+")) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"无法核实商家买家身份");
        try {
            int inserted=db.update("""
                insert into merchant_binding(shop,buyer,customer_gid) values (?,?,?)
                on conflict(shop,buyer) do update set customer_gid=excluded.customer_gid
                where merchant_binding.customer_gid=excluded.customer_gid
                """,shop,buyer.getName(),gid);
            if (inserted==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"账户已绑定另一商家身份");
        } catch(DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该商家身份已绑定其他账户");
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/#/merchant")).cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping
    void unlink(java.security.Principal buyer,HttpSession session) {
        db.update("delete from merchant_binding_flow where buyer=? and session_id=?",buyer.getName(),session.getId());
        db.update("delete from merchant_binding where shop=? and buyer=?",shop,buyer.getName());
    }
    @Scheduled(fixedDelay=3600000)
    void expire() { db.update("delete from merchant_binding_flow where expires_at<now()"); }

    private JsonNode send(URI endpoint,String type,String payload,String access) throws IOException,InterruptedException {
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(6))
            .header("Content-Type",type).header("User-Agent","ShopWise/1.0").POST(HttpRequest.BodyPublishers.ofString(payload));
        if (access!=null) request.header("Authorization",access);
        var result=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if (result.statusCode()!=200) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"商家身份服务暂不可用");
        return json.readTree(result.body());
    }
    private String secret() {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static URI https(String raw) {
        var uri=URI.create(raw);
        if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null)
            throw new IllegalArgumentException("Customer account URLs must be explicit HTTPS endpoints");
        return uri;
    }
    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String form(Map<String,String> parameters) {
        return parameters.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+
            URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
}
