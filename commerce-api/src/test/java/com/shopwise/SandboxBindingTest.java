package com.shopwise;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "SHOPWISE_SANDBOX_BUYER_PASSWORD=fixture-only-buyer-918",
    "SHOPWISE_SANDBOX_STAFF_PASSWORD=fixture-only-staff-832",
    "SHOPIFY_PILOT_SHOP=sandbox-check.myshopify.com",
    "SHOPIFY_WEBHOOK_SECRET=fixture-webhook-secret-19",
    "SHOPIFY_CUSTOMER_CLIENT_ID=fixture-client",
    "SHOPIFY_CUSTOMER_AUTHORIZE_URL=https://example.com/auth",
    "SHOPIFY_CUSTOMER_TOKEN_URL=https://example.com/token",
    "SHOPIFY_CUSTOMER_GRAPHQL_URL=https://example.com/graph",
    "SHOPIFY_CUSTOMER_REDIRECT_URL=https://example.com/api/v1/merchant/binding/callback"
})
@ActiveProfiles("sandbox")
@EnabledIfEnvironmentVariable(named="SHOPWISE_DB_TESTS",matches="true")
class SandboxBindingTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private final HttpClient http=HttpClient.newHttpClient();

    @Test void jdbcLoginStartsBoundOAuthFlowWithoutUsingDemoAccounts() throws Exception {
        var first=send("/api/csrf",null,null,null);
        String cookie=cookie(first);
        String csrf=json.readTree(first.body()).path("token").asString();
        var login=send("/api/login",cookie,csrf,Map.of("username","sandbox-buyer","password","fixture-only-buyer-918"));
        assertEquals(200,login.statusCode());
        assertNotEquals(cookie,cookie(login));
        cookie=cookie(login);
        csrf=json.readTree(send("/api/csrf",cookie,null,null).body()).path("token").asString();
        var start=send("/api/v1/merchant/binding/start",cookie,csrf,Map.of());
        assertEquals(200,start.statusCode());
        assertTrue(start.body().contains("code_challenge_method=S256"));
        assertTrue(db.queryForObject("select count(*) from merchant_binding_flow where buyer='sandbox-buyer'",Integer.class)>0);
        assertEquals(400,send("/api/v1/merchant/binding/callback?state=invalid&code=test",cookie,null,null).statusCode());
        assertEquals(403,send("/api/v1/merchant/staff/returns",cookie,null,null).statusCode());
        var demo=send("/api/login",cookie,csrf,Map.of("username","buyer","password","ShopWise123!"));
        assertEquals(401,demo.statusCode());
    }

    private String cookie(HttpResponse<?> response) {
        String value=response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(value.contains("Secure"));
        assertTrue(value.contains("SameSite=Lax"));
        return value.split(";",2)[0];
    }

    private HttpResponse<String> send(String path,String cookie,String csrf,Map<String,String> body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if (cookie!=null) request.header("Cookie",cookie);
        if (csrf!=null) request.header("X-CSRF-TOKEN",csrf);
        if (body!=null) request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
