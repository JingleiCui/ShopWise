package com.shopwise;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Configuration
class SecurityConfig {
  @Bean
  UserDetailsService users(javax.sql.DataSource data, org.springframework.core.env.Environment environment) {
    if (environment.matchesProfiles("prod", "sandbox")) return new org.springframework.security.provisioning.JdbcUserDetailsManager(data);
    var encoder = new BCryptPasswordEncoder();
    var users = new InMemoryUserDetailsManager();
    for (String name : new String[] {"buyer", "buyer2", "staff"})
      users.createUser(
          User.withUsername(name)
              .password("{bcrypt}" + encoder.encode("ShopWise123!"))
              .roles(name.equals("staff") ? "STAFF" : "BUYER")
              .build());
    return users;
  }

  @Bean
  AuthenticationManager authenticationManager(UserDetailsService users) {
    return new ProviderManager(
        new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, AgentGrants grants, org.springframework.core.env.Environment env) throws Exception {
    http.addFilterBefore(grants.filter(), org.springframework.security.web.csrf.CsrfFilter.class)
        .csrf(csrf -> csrf.ignoringRequestMatchers(req -> Boolean.TRUE.equals(req.getAttribute(AgentGrants.VERIFIED)) ||
            ("POST".equals(req.getMethod()) && "/webhooks/shopify".equals(req.getRequestURI()))))
        .logout(logout -> logout.disable())
        .authorizeHttpRequests(
            auth -> {
                auth.requestMatchers(HttpMethod.POST,"/api/v1/assistant/turn","/webhooks/shopify").permitAll();
                auth.requestMatchers("/api/v1/merchant/staff/**").hasRole("STAFF");
                auth.requestMatchers("/api/v1/merchant/**").hasRole("BUYER");
                auth.requestMatchers(HttpMethod.GET,"/api/csrf","/api/v1/discovery","/api/v1/discovery/**").permitAll();
                if (env.matchesProfiles("prod", "sandbox")) {
                  auth.requestMatchers("/api/products/**","/api/chat/**","/api/policies","/api/cart/**","/api/quotes/**","/api/orders/**","/api/returns/**","/api/staff/**").denyAll();
                }
                auth.requestMatchers("/api/login", "/api/health", "/error")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET, "/api/products", "/api/products/*", "/api/policies")
                    .permitAll()
                    .requestMatchers(
                        "/api/staff/**",
                        "/api/orders/*/ship",
                        "/api/returns/*/approve",
                        "/api/returns/*/reject",
                        "/api/returns/*/refund")
                    .hasRole("STAFF")
                    .requestMatchers(
                        "/api/chat",
                        "/api/chat/**",
                        "/api/cart/**",
                        "/api/quotes/**",
                        "/api/orders/*/pay",
                        "/api/orders/*/cancel",
                        "/api/orders/*/receive",
                        "/api/orders/*/returns")
                    .hasRole("BUYER")
                    .anyRequest()
                    .authenticated(); })
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint((req, res, ex) -> error(res, 401, "请先登录"))
                    .accessDeniedHandler((req, res, ex) -> error(res, 403, "当前身份无权执行此操作")));
    return http.build();
  }

  private static void error(HttpServletResponse res, int status, String message)
      throws java.io.IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write("{\"message\":\"" + message + "\"}");
  }
}

@RestController
@RequestMapping("/api")
class SessionController {
  private final AuthenticationManager users;

  private final AgentGrants grants;
  SessionController(AuthenticationManager users, AgentGrants grants) {
    this.grants = grants;
    this.users = users;
  }

  record Login(String username, String password) {}

  @PostMapping("/login")
  Map<String, String> login(
      @RequestBody Login login, HttpServletRequest req, HttpServletResponse res) {
    Authentication user;
    try {
      user =
          users.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(login.username, login.password));
    } catch (org.springframework.security.core.AuthenticationException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }
    if (req.getSession(false) != null) {
      grants.logout(req.getSession(false).getId());
      req.getSession(false).invalidate();
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(user);
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, req, res);
    return me(user);
  }

  @GetMapping("/me")
  Map<String, String> me(Authentication user) {
    return Map.of("username", user.getName(), "role", staff(user) ? "STAFF" : "BUYER");
  }

  static boolean staff(Authentication user) {
    return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_STAFF"));
  }

  @PostMapping("/logout")
  Map<String, Boolean> logout(HttpServletRequest req) {
    if (req.getSession(false) != null) {
      grants.logout(req.getSession(false).getId());
      req.getSession(false).invalidate();
    }
    SecurityContextHolder.clearContext();
    return Map.of("ok", true);
  }

  @GetMapping("/csrf")
  Map<String,String> csrf(org.springframework.security.web.csrf.CsrfToken token, HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    return Map.of("header",token.getHeaderName(),"token",token.getToken());
  }

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "UP");
  }
}

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(ResponseStatusException.class)
  org.springframework.http.ResponseEntity<Map<String, String>> failed(ResponseStatusException e) {
    return org.springframework.http.ResponseEntity.status(e.getStatusCode())
        .body(Map.of("message", e.getReason() == null ? "请求失败" : e.getReason()));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  org.springframework.http.ResponseEntity<Map<String, String>> malformed(Exception e) {
    return org.springframework.http.ResponseEntity.badRequest().body(Map.of("message", "请求参数格式无效"));
  }
}
