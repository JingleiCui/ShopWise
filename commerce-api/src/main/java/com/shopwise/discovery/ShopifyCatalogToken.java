package com.shopwise.discovery;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class ShopifyCatalogToken {
    private static final URI ENDPOINT=URI.create("https://api.shopify.com/auth/access_token");
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String id,secret,staticToken;
    private Instant expires=Instant.EPOCH;
    private String cached="";

    ShopifyCatalogToken(ObjectMapper json,@Value("${SHOPIFY_CATALOG_CLIENT_ID:}") String id,
                          @Value("${SHOPIFY_CATALOG_CLIENT_SECRET:}") String secret,
                          @Value("${SHOPIFY_CATALOG_TOKEN:}") String staticToken) {
        this.json=json; this.id=id; this.secret=secret; this.staticToken=staticToken;
    }

    synchronized String get() throws IOException,InterruptedException {
        if (id.isBlank() && secret.isBlank()) return staticToken;
        if (id.isBlank() || secret.isBlank()) throw new IOException("Incomplete Shopify catalog credentials");
        if (expires.isAfter(Instant.now())) return cached;
        var request=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                "client_id",id,"client_secret",secret,"grant_type","client_credentials")))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) throw new IOException("Shopify catalog authentication failed");
        var data=json.readTree(response.body());
        String token=data.path("access_token").asString("");
        if (token.isBlank()) throw new IOException("Shopify catalog token missing");
        cached=token;
        expires=Instant.now().plusSeconds(Math.max(0,Math.min(3600,data.path("expires_in").asLong(3600))-60));
        return cached;
    }

    synchronized void invalidate(String token) {
        if (token.equals(cached)) expires=Instant.EPOCH;
    }
}
