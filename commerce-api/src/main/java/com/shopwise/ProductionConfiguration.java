package com.shopwise;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile("prod")
class ProductionConfiguration {
    @org.springframework.context.annotation.Bean
    org.springframework.session.web.http.CookieSerializer productionCookieSerializer() {
        var cookie=new org.springframework.session.web.http.DefaultCookieSerializer();
        cookie.setUseSecureCookie(true);
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        return cookie;
    }

    ProductionConfiguration(Environment env) {
        if (env.matchesProfiles("load-fixture")) throw new IllegalStateException("Load fixtures are unavailable in production");
        String secret=env.getProperty("SERVICE_KEY","");
        if (secret.length()<32 || "local-shopwise-agent".equals(secret))
            throw new IllegalStateException("Production requires SERVICE_KEY with at least 32 characters");
        if ("shopwise".equals(env.getProperty("DB_PASSWORD","shopwise")) || env.getProperty("DB_PASSWORD","").isBlank())
            throw new IllegalStateException("Production requires a non-default database password");
        if (env.getProperty("SHOPIFY_ENABLED",Boolean.class,true)) {
            String profile=env.getProperty("SHOPIFY_AGENT_PROFILE","");
            if (!profile.startsWith("https://") || profile.startsWith("https://shopify.dev/") ||
                (env.getProperty("SHOPIFY_CATALOG_CLIENT_ID","").isBlank() ||
                 env.getProperty("SHOPIFY_CATALOG_CLIENT_SECRET","").isBlank()))
                throw new IllegalStateException("Production Shopify discovery requires a registered profile and catalog client credentials");
        }
        if (env.getProperty("EBAY_ENABLED",Boolean.class,false) &&
            (env.getProperty("EBAY_SANDBOX",Boolean.class,true) || env.getProperty("EBAY_CLIENT_ID","").isBlank() ||
                env.getProperty("EBAY_CLIENT_SECRET","").isBlank()))
            throw new IllegalStateException("Production eBay discovery requires approved live credentials");
    }
}
