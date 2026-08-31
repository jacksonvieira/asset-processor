package pt.piscapisca.b2c.asset.processor.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AppConfig(CloudConfig cloud, B2cConfig b2c, DatabaseConfig database) {

	public record DatabaseConfig(
			String url,
			String username,
			String password,
			@JsonProperty( "minimum-idle" ) int minimumIdle,
			@JsonProperty( "maximum-pool-size" ) int maximumPoolSize,
			@JsonProperty( "connection-timeout-ms" ) long connectionTimeoutMs,
			@JsonProperty( "idle-timeout" ) long idleTimeout,
			@JsonProperty( "max-lifetime" ) long maxLifetime,
			@JsonProperty( "pool-name" ) String poolName,
			@JsonProperty( "connection-init-sql" ) String connectionInitSql,
			String schema
	) { }

	public record CloudConfig(AwsConfig aws) {

		public record AwsConfig(RegionConfig region, CredentialsConfig credentials, S3Config s3) {

			public record RegionConfig(@JsonProperty( "static" ) String staticRegion) {

			}

			// Ajustado para refletir o objeto aninhado { profile: { name: "..." } }
			public record CredentialsConfig(ProfileConfig profile) {

				public record ProfileConfig(String name) {

				}
			}

			public record S3Config(boolean pathStyleAccessEnabled) {

			}
		}
	}

	public record B2cConfig(CompaniesConfig companies) {

		public record CompaniesConfig(EfsConfig efs) {

			public record EfsConfig(GarbageCollectorConfig garbageCollector) {

				public record GarbageCollectorConfig(
						@JsonProperty( "source-type" ) String sourceType,
						@JsonProperty( "local-path" ) String localPath,
						@JsonProperty( "quarantine-path" ) String quarantinePath,
						@JsonProperty( "default-parallel-files" ) int defaultParallelFiles,
						@JsonProperty( "max-parallel-files" ) int maxParallelFiles,
						@JsonProperty( "worker-threads" ) int workerThreads,
						S3PropertiesConfig s3
				) {

				}
			}
		}
	}

	public record S3PropertiesConfig(
			String bucket,
			@JsonProperty( "companies-prefix" ) String companiesPrefix,
			@JsonProperty( "persons-prefix" ) String personsPrefix,
			@JsonProperty( "read-buffer-size" ) int readBufferSize,
			@JsonProperty( "api-call-attempt-timeout-seconds" ) int apiCallAttemptTimeoutSeconds,
			@JsonProperty( "processed-tag-key" ) String processedTagKey,
			@JsonProperty( "tagging-lookup-parallelism" ) int taggingLookupParallelism
	) {

	}
}
