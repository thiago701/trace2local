package tech.neural7.trace2local.examples.pix.infra;

import org.postgresql.ds.PGSimpleDataSource;
import tech.neural7.trace2local.jdbc.Trace2LocalJdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Ledger no Postgres (RDS na AWS — via RDS Proxy em produção; container no local).
 * DataSource envolvido pelo Trace2Local: cada SQL vira nó na árvore, com o texto
 * sem literais (redigido) e a tabela.
 */
public final class Db {

    private static volatile DataSource dataSource;

    private Db() {}

    public static Connection connection() throws SQLException {
        if (dataSource == null) {
            synchronized (Db.class) {
                if (dataSource == null) {
                    PGSimpleDataSource pg = new PGSimpleDataSource();
                    pg.setURL(Env.get("PIX_DB_URL", "jdbc:postgresql://postgres:5432/pix"));
                    pg.setUser(Env.get("PIX_DB_USER", "pix"));
                    pg.setPassword(Env.get("PIX_DB_PASSWORD", ""));
                    pg.setConnectTimeout(3);
                    pg.setSocketTimeout(10);
                    pg.setApplicationName(Env.get("AWS_LAMBDA_FUNCTION_NAME", "finance-pix"));
                    dataSource = Trace2LocalJdbc.wrap(pg, Aws.CFG);
                }
            }
        }
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
