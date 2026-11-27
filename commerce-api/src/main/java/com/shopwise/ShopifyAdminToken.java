package com.shopwise;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class ShopifyAdminToken {
    private final String shop, staticToken, id, secret;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String cached="";
    private Instant expires=Instant.EPOCH;

    ShopifyAdminToken(ObjectMapper json,@Value("${SHOPIFY_PILOT_SHOP:}") String shop,
                      @Value("${SHOPIFY_ADMIN_TOKEN:}") String staticToken,
                      @Value("${SHOPIFY_ADMIN_CLIENT_ID:}") String id,
                      @Value("${SHOPIFY_ADMIN_CLIENT_SECRET:}") String secret) {
        if (id.isBlank()!=secret.isBlank()) throw new IllegalArgumentException("Configure both Shopify Admin client credentials");
        if ((!id.isBlank() || !staticToken.isBlank()) && !shop.matches("[a-z0-9][a-z0-9-]*\\.myshopify\\.com"))
            throw new IllegalArgumentException("Shopify Admin credentials require a verified shop domain");
        this.json=json; this.shop=shop; this.staticToken=staticToken; this.id=id; this.secret=secret;
    }

    boolean enabled() { return !id.isBlank() || !staticToken.isBlank(); }

    synchronized String get() throws IOException,InterruptedException {
        if (id.isBlank()) return staticToken;
        if (Instant.now().isBefore(expires)) return cached;
        var request=HttpRequest.newBuilder(URI.create("https://"+shop+"/admin/oauth/access_token"))
            .timeout(Duration.ofSeconds(4)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("client_id",id,
                "client_secret",secret,"grant_type","client_credentials")))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) throw new IOException("Shopify Admin token exchange failed");
        var body=json.readTree(response.body());
        String token=body.path("access_token").asString("");
        if (token.isBlank()) throw new IOException("Shopify Admin token missing");
        cached=token;
        expires=Instant.now().plusSeconds(Math.max(0,Math.min(86400,body.path("expires_in").asLong(86400))-60));
        return cached;
    }

    synchronized void invalidate() { expires=Instant.EPOCH; }
}
