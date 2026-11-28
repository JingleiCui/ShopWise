package com.shopwise;

import java.sql.*;
import java.util.UUID;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
class ConversationLocks {
    private final HikariDataSource data;
    ConversationLocks(Environment env) {
        HikariConfig config=new HikariConfig();
        config.setPoolName("conversation-locks");
        config.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        config.setUsername(env.getRequiredProperty("spring.datasource.username"));
        config.setPassword(env.getRequiredProperty("spring.datasource.password"));
        config.setMaximumPoolSize(24);
        config.setMinimumIdle(4);
        config.setConnectionTimeout(2000);
        this.data=new HikariDataSource(config);
    }
    @PreDestroy void close() { data.close(); }
    // Keep the advisory lock on a dedicated connection and release it before returning to the pool.
    Handle acquire(UUID id) {
        long key=id.getLeastSignificantBits() ^ id.getMostSignificantBits();
        try {
            var c=data.getConnection();
            try (var s=c.prepareStatement("select pg_try_advisory_lock(?)")) {
                s.setLong(1,key);
                try (var rs=s.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) throw new ResponseStatusException(HttpStatus.CONFLICT,"会话正在处理上一条消息，请稍后重试");
                }
                return new Handle(c,key,data);
            } catch(Exception e) { c.close(); throw e; }
        } catch(SQLException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"会话服务繁忙",e); }
    }
    record Handle(Connection connection,long key,HikariDataSource owner) implements AutoCloseable {
        public void close() {
            try(var s=connection.prepareStatement("select pg_advisory_unlock(?)")) {
                s.setLong(1,key);
                s.execute();
            } catch(SQLException e) {
                owner.evictConnection(connection);
            } finally {
                try { connection.close(); }
                catch(SQLException e) { owner.evictConnection(connection); }
            }
        }
    }
}
