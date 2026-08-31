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

public class S3ClientFactory {

	/**
	 * Cria e configura uma instância otimizada do S3Client utilizando o record de configuração.
	 *
	 * @param awsConfig Configurações de Cloud/AWS mapeadas do YAML.
	 * @return {@link S3Client} configurado.
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

	private static AwsCredentialsProvider resolveCredentialsProvider(String awsProfile) {
		if (awsProfile != null && !awsProfile.isBlank()) {
			return ProfileCredentialsProvider.create(awsProfile);
		}
		// Na EC2, isso pegará automaticamente a IAM Role associada à instância
		return DefaultCredentialsProvider.builder().build();
	}
}
