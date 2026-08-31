package pt.piscapisca.b2c.asset.processor.infra.factory;

import pt.piscapisca.b2c.asset.processor.config.AppConfig;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.time.Duration;

/**
 * Factory responsible for creating and configuring optimized {@link S3Client} instances
 * using the application's AWS configuration properties.
 */
public class S3ClientFactory {

	/**
	 * Creates and configures an optimized {@link S3Client} instance with custom timeouts,
	 * Apache HTTP connection pool settings, path-style access rules, and credential providers.
	 *
	 * @param awsConfig cloud and AWS configuration mapping properties from the YAML configuration
	 * @return a fully configured {@link S3Client} instance
	 */
	public static S3Client create( AppConfig.CloudConfig.AwsConfig awsConfig) {
		String regionName = awsConfig.region().staticRegion();

		String awsProfile = awsConfig.credentials().profile().name();
		boolean pathStyleAccess = awsConfig.s3().pathStyleAccessEnabled();

		String resolvedRegion = (regionName != null && !regionName.isBlank()) ? regionName : "eu-west-1";

		return S3Client.builder()
				.region(Region.of(resolvedRegion))
				.credentialsProvider(resolveCredentialsProvider(awsProfile))
				.httpClientBuilder(ApacheHttpClient.builder()
						.maxConnections(50)
						.connectionTimeout(Duration.ofSeconds(10))
						.socketTimeout(Duration.ofMinutes(5))
				)
				.overrideConfiguration(ClientOverrideConfiguration.builder()
						.apiCallTimeout(Duration.ofMinutes(30))
						.apiCallAttemptTimeout(Duration.ofMinutes(5))
						.build())
				.serviceConfiguration(S3Configuration.builder()
						.pathStyleAccessEnabled(pathStyleAccess)
						.build())
				.build();
	}

	/**
	 * Resolves the appropriate {@link AwsCredentialsProvider} based on the profile configuration.
	 * Falls back to {@link DefaultCredentialsProvider} (e.g., retrieving the instance IAM Role on EC2 or ECS)
	 * if no explicit profile is provided.
	 *
	 * @param awsProfile optional AWS profile name
	 * @return the resolved {@link AwsCredentialsProvider}
	 */
	private static AwsCredentialsProvider resolveCredentialsProvider(String awsProfile) {
		if (awsProfile != null && !awsProfile.isBlank()) {
			return ProfileCredentialsProvider.create(awsProfile);
		}
		// On EC2/ECS, this automatically falls back to the IAM Role associated with the instance
		return DefaultCredentialsProvider.builder().build();
	}
}
