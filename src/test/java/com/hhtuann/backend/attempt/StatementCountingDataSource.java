package com.hhtuann.backend.attempt;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link DataSource} wrapper that counts every executed JDBC statement regardless of the
 * caller (Hibernate entity loads/JPQL/native queries AND raw {@code JdbcTemplate}). Used by the
 * attempt query-count tests to assert bounded, N-independent statement counts at the datasource
 * level (no N+1).
 *
 * <p>Counting is on the execute family ({@code execute}, {@code executeQuery}, {@code executeUpdate},
 * {@code executeBatch}, {@code executeLargeUpdate}) on {@code Statement}/{@code PreparedStatement}/
 * {@code CallableStatement}. {@code addBatch} is not counted (only actual execution is).
 */
public class StatementCountingDataSource extends DelegatingDataSource {

    private static final Set<String> COUNTED = Set.of(
            "execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeUpdate");

    private final AtomicInteger counter;

    public StatementCountingDataSource(DataSource delegate, AtomicInteger counter) {
        super(delegate);
        this.counter = counter;
    }

    public int count() {
        return counter.get();
    }

    public void reset() {
        counter.set(0);
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(connection, counter));
    }

    private static Statement wrapStatement(Statement raw, AtomicInteger counter) {
        Class<?> iface = (raw instanceof CallableStatement) ? CallableStatement.class
                : (raw instanceof PreparedStatement) ? PreparedStatement.class : Statement.class;
        InvocationHandler handler = (proxy, method, args) -> {
            if (COUNTED.contains(method.getName())) {
                counter.incrementAndGet();
            }
            return method.invoke(raw, args);
        };
        return (Statement) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** Connection invocation handler: wraps any created Statement/PreparedStatement/CallableStatement. */
    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection real;
        private final AtomicInteger counter;

        ConnectionHandler(Connection real, AtomicInteger counter) {
            this.real = real;
            this.counter = counter;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(real, args);
            if (result instanceof Statement) {
                return wrapStatement((Statement) result, counter);
            }
            return result;
        }
    }
}
