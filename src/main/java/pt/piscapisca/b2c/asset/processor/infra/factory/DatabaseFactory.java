package pt.piscapisca.b2c.asset.processor.infra.factory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import pt.piscapisca.b2c.asset.processor.config.AppConfig;

/**
 * Factory responsible for initializing and managing the HikariCP connection pool
 * and providing configured jOOQ {@link DSLContext} instances for PostgreSQL persistence.
 */
public class DatabaseFactory {

	private static HikariDataSource dataSource;

	/**
	 * Initializes the HikariCP connection pool based on the provided database configuration
	 * and returns a PostgreSQL-dialect jOOQ {@link DSLContext}.
	 *
	 * @param dbConfig configuration properties containing JDBC URL, credentials, pool sizes, and timeouts
	 * @return a configured {@link DSLContext} ready for executing database operations
	 */
	public static DSLContext createDSLContext( AppConfig.DatabaseConfig dbConfig ) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl( dbConfig.url() );
		config.setUsername( dbConfig.username() );
		config.setPassword( dbConfig.password() );

		config.setMinimumIdle( dbConfig.minimumIdle() );
		config.setMaximumPoolSize( dbConfig.maximumPoolSize() );
		config.setConnectionTimeout( dbConfig.connectionTimeoutMs() );
		config.setIdleTimeout( dbConfig.idleTimeout() );
		config.setMaxLifetime( dbConfig.maxLifetime() );
		config.setPoolName( dbConfig.poolName() );

		if ( dbConfig.connectionInitSql() != null && !dbConfig.connectionInitSql().isBlank() ) {
			config.setConnectionInitSql( dbConfig.connectionInitSql() );
		}

		if ( dbConfig.schema() != null && !dbConfig.schema().isBlank() ) {
			config.setSchema( dbConfig.schema() );
		}

		dataSource = new HikariDataSource( config );

		return DSL.using( dataSource, SQLDialect.POSTGRES );
	}

	/**
	 * Gracefully closes the underlying HikariCP connection pool during application shutdown.
	 */
	public static void close() {
		if ( dataSource != null && !dataSource.isClosed() ) {
			dataSource.close();
		}
	}
}
