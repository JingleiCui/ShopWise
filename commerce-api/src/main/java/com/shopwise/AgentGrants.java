package com.shopwise;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class AgentGrants {
    static final String HEADER="X-ShopWise-Grant";
    static final String VERIFIED=AgentGrants.class.getName()+".verified";
    private final JdbcTemplate db;
    AgentGrants(JdbcTemplate db) { this.db=db; }
    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    String issue(String buyer,String session,UUID request) {
        String token=UUID.randomUUID()+"."+UUID.randomUUID();
        db.update("insert into agent_grants values (?,?,?,?,now()+interval '100 seconds')",digest(token),buyer,session,request);
        return token;
    }
    void revoke(String token) { db.update("delete from agent_grants where digest=?",digest(token)); }
    void logout(String session) { db.update("delete from agent_grants where session_id=?",session); }
    @Scheduled(fixedDelay=120000) void expire() { db.update("delete from agent_grants where expires_at<now()"); }

    private boolean permitted(String method,String path) {
        if ("GET".equals(method)) return path.matches("/api/(products|policies|cart|orders(?:/[A-Za-z0-9-]+)?|quotes/[A-Za-z0-9-]+|v1/discovery(?:/[a-z]+/(?:lookup|details))?)");
        return "POST".equals(method) && path.matches("/api/(cart/items/[0-9]+/add|quotes|orders/[A-Za-z0-9-]+/returns)");
    }
    OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
                var token=req.getHeader(HEADER);
                if (token==null) { chain.doFilter(req,res); return; }
                if (token.length()>150 || !permitted(req.getMethod(),req.getRequestURI())) { res.sendError(403); return; }
                var buyers=db.queryForList("select buyer from agent_grants where digest=? and request_id::text=? and expires_at>now()",String.class,
                    digest(token),req.getHeader("Idempotency-Key"));
                if (buyers.size()!=1) { res.sendError(403); return; }
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(buyers.get(0),null,List.of(new SimpleGrantedAuthority("ROLE_BUYER"))));
                SecurityContextHolder.setContext(context);
                req.setAttribute(VERIFIED,true);
                chain.doFilter(req,res);
            }
        };
    }
}
