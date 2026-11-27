package com.shopwise;

import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
class ConversationLocks {
    private final DataSource data;
    ConversationLocks(DataSource data) { this.data=data; }
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
                return new Handle(c,key);
            } catch(Exception e) { c.close(); throw e; }
        } catch(SQLException e) { throw new IllegalStateException("conversation lock failed",e); }
    }
    record Handle(Connection connection,long key) implements AutoCloseable {
        public void close() {
            try(var c=connection; var s=c.prepareStatement("select pg_advisory_unlock(?)")) { s.setLong(1,key); s.execute(); }
            catch(SQLException e) { throw new IllegalStateException("conversation unlock failed",e); }
        }
    }
}
