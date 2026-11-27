package com.shopwise;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@Profile("sandbox & !prod")
class SandboxConfiguration implements ApplicationRunner {
    private final JdbcTemplate db;
    private final String shop, buyerPassword, staffPassword;

    SandboxConfiguration(JdbcTemplate db, Environment env,
            @Value("${SHOPIFY_PILOT_SHOP:}") String shop,
            @Value("${SHOPWISE_SANDBOX_BUYER_PASSWORD:}") String buyerPassword,
            @Value("${SHOPWISE_SANDBOX_STAFF_PASSWORD:}") String staffPassword) {
        if (env.matchesProfiles("load-fixture") ||
            (env.getProperty("EBAY_ENABLED", Boolean.class, false) && !env.getProperty("EBAY_SANDBOX", Boolean.class, true)))
            throw new IllegalStateException("Sandbox cannot use load fixtures or live eBay credentials");
        if (!valid(buyerPassword) || !valid(staffPassword) || buyerPassword.equals(staffPassword))
            throw new IllegalStateException("Sandbox requires distinct buyer/staff passwords of 16 to 72 UTF-8 bytes");
        this.db = db;
        this.shop = shop;
        this.buyerPassword = buyerPassword;
        this.staffPassword = staffPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        upsert("sandbox-buyer", buyerPassword, "ROLE_BUYER");
        upsert("sandbox-staff", staffPassword, "ROLE_STAFF");
        if (!shop.isBlank())
            db.update("insert into merchant_staff(shop,username) values (?,?) on conflict do nothing", shop, "sandbox-staff");
    }

    private static boolean valid(String value) {
        int size = value.getBytes(StandardCharsets.UTF_8).length;
        return !value.isBlank() && size >= 16 && size <= 72;
    }

    private void upsert(String name, String password, String role) {
        var encoder = new BCryptPasswordEncoder();
        var hashes = db.queryForList("select password from users where username=?", String.class, name);
        if (hashes.isEmpty() || !hashes.get(0).startsWith("{bcrypt}") ||
            !encoder.matches(password, hashes.get(0).substring(8))) {
            db.update("""
                insert into users(username,password) values (?,?) on conflict(username) do update
                set password=excluded.password
                """, name, "{bcrypt}" + encoder.encode(password));
        }
        db.update("insert into authorities(username,authority) values (?,?) on conflict do nothing", name, role);
    }
}

@Configuration
@Profile("prod | sandbox")
class HostedSessionConfiguration {
    @Bean
    CookieSerializer hostedCookieSerializer() {
        var cookie = new DefaultCookieSerializer();
        cookie.setUseSecureCookie(true);
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        return cookie;
    }
}
