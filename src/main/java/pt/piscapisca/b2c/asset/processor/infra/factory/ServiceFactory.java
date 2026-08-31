package pt.piscapisca.b2c.asset.processor.infra.factory;

import org.jooq.DSLContext;
import pt.piscapisca.b2c.asset.processor.config.AppConfig;
import pt.piscapisca.b2c.asset.processor.domain.source.LogFileSource;
import pt.piscapisca.b2c.asset.processor.execution.EfsFileProcessor;
import pt.piscapisca.b2c.asset.processor.executor.*;
import pt.piscapisca.b2c.asset.processor.infra.EfsGarbageCollectorProperties;
import pt.piscapisca.b2c.asset.processor.infra.EfsGarbageCollectorS3Properties;
import pt.piscapisca.b2c.asset.processor.infra.persistence.*;
import pt.piscapisca.b2c.asset.processor.infra.source.LocalLogFileSource;
import pt.piscapisca.b2c.asset.processor.infra.source.S3LogFileSourceImpl;
import pt.piscapisca.b2c.asset.processor.parser.PathDataExtractor;
import pt.piscapisca.b2c.asset.processor.service.AssetLookupCacheService;
import pt.piscapisca.b2c.asset.processor.service.AssetOrphaningService;
import pt.piscapisca.b2c.asset.processor.service.EfsGarbageCollectorService;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

/**
 * Factory responsible for wiring and instantiating the main garbage collection service
 * along with all its required infrastructure components, repositories, and executors.
 */
public class ServiceFactory {

	/**
	 * Creates and returns the main Garbage Collection service with all dependencies fully injected
	 * based on the provided configuration and database context.
	 *
	 * @param dsl    the jOOQ {@link DSLContext} for database persistence operations
	 * @param config the application configuration mapping properties from YAML
	 * @return a fully initialized {@link EfsGarbageCollectorService} instance
	 */
	public static EfsGarbageCollectorService createGarbageCollectorService(
			DSLContext dsl,
			AppConfig config
	) {
		var gcConfig = config.b2c().companies().efs().garbageCollector();

		EfsGarbageCollectorProperties gcProperties = new EfsGarbageCollectorProperties(
				gcConfig.quarantinePath(),
				gcConfig.defaultParallelFiles(),
				gcConfig.maxParallelFiles(),
				gcConfig.workerThreads()
		);

		LogFileSource logFileSource;
		String sourceType = gcConfig.sourceType() != null ? gcConfig.sourceType().trim().toUpperCase() : "S3";

		if ( "LOCAL".equals( sourceType ) ) {
			// If LOCAL, no S3Client is created or allocated in memory
			logFileSource = new LocalLogFileSource( gcConfig.localPath() );
		}
		else {
			// If S3, the client is created on-demand and encapsulated within the source provider
			S3Client s3Client = S3ClientFactory.create( config.cloud().aws() );
			var s3Yaml = gcConfig.s3();

			EfsGarbageCollectorS3Properties s3Props = new EfsGarbageCollectorS3Properties(
					s3Yaml.bucket(),
					s3Yaml.companiesPrefix(),
					s3Yaml.personsPrefix(),
					s3Yaml.readBufferSize(),
					s3Yaml.apiCallAttemptTimeoutSeconds(),
					s3Yaml.processedTagKey(),
					s3Yaml.taggingLookupParallelism()
			);

			logFileSource = new S3LogFileSourceImpl( s3Client, s3Props );
		}

		AssetLookupCacheService lookupCacheService = createAssetLookupCacheService( dsl );
		EfsFileProcessor efsFileProcessor = createEfsFileProcessor( dsl, lookupCacheService, gcProperties );

		return new EfsGarbageCollectorService(
				gcProperties,
				efsFileProcessor,
				lookupCacheService,
				logFileSource
		);
	}

	/**
	 * Instantiates and configures the {@link EfsFileProcessor} with its required data extractors,
	 * orphaning services, and action executors registry.
	 *
	 * @param dsl                the jOOQ database context
	 * @param lookupCacheService the asset cache service for fast validation
	 * @param gcProperties       garbage collection runtime properties
	 * @return a configured {@link EfsFileProcessor} instance
	 */
	private static EfsFileProcessor createEfsFileProcessor(
			DSLContext dsl,
			AssetLookupCacheService lookupCacheService,
			EfsGarbageCollectorProperties gcProperties
	) {
		AssetRepository assetRepository = new AssetRepository( dsl );
		AssetOrphaningService orphaningService = new AssetOrphaningService( lookupCacheService, assetRepository );
		PathDataExtractor dataExtractor = new PathDataExtractor();

		DeleteOrphanActionExecutor deleteExecutor = new DeleteOrphanActionExecutor();
		QuarantineOrphanActionExecutor quarantineExecutor = new QuarantineOrphanActionExecutor( gcProperties );
		WriteToRemoveFileOrphanActionExecutor writeToRemoveFileOrphanActionExecutor = new WriteToRemoveFileOrphanActionExecutor();

		List<OrphanActionExecutor> executors = List.of( deleteExecutor, quarantineExecutor,
				writeToRemoveFileOrphanActionExecutor );
		OrphanActionExecutorRegistry executorRegistry = new OrphanActionExecutorRegistry( executors );

		return new EfsFileProcessor( dataExtractor, orphaningService, executorRegistry );
	}

	/**
	 * Instantiates the {@link AssetLookupCacheService} injecting all required domain repositories.
	 *
	 * @param dsl the jOOQ database context
	 * @return a configured {@link AssetLookupCacheService} instance
	 */
	private static AssetLookupCacheService createAssetLookupCacheService( DSLContext dsl ) {
		return new AssetLookupCacheService(
				new VehicleRepository( dsl ),
				new StandRepository( dsl ),
				new PersonRepository( dsl ),
				new CompanyRepository( dsl )
		);
	}
}
