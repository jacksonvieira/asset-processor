package pt.piscapisca.b2c.asset.processor.infra.factory;

import lombok.extern.slf4j.Slf4j;
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
import pt.piscapisca.b2c.asset.processor.infra.statistics.ProcessingStatisticsCollector;
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
@Slf4j
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

		log.info( "EFS GC config | defaultParallelFiles={} | maxParallelFiles={} | workerThreads={} | maximumPoolSize={}",
				gcConfig.defaultParallelFiles(), gcConfig.maxParallelFiles(), gcConfig.workerThreads(),
				config.database().maximumPoolSize() );
		EfsGarbageCollectorProperties gcProperties = new EfsGarbageCollectorProperties(
				gcConfig.quarantinePath(),
				gcConfig.defaultParallelFiles(),
				gcConfig.maxParallelFiles(),
				gcConfig.workerThreads(),
				config.database().maximumPoolSize()
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

		// The statistics collector is shared between the processor (writes) and the service (reads the report).
		// It is reset at the start of each run, so there is no cross-run contamination.
		ProcessingStatisticsCollector statisticsCollector = new ProcessingStatisticsCollector();
		AssetLookupCacheService lookupCacheService = createAssetLookupCacheService( dsl );
		EfsFileProcessor efsFileProcessor = createEfsFileProcessor( lookupCacheService, gcProperties,
				statisticsCollector );

		return new EfsGarbageCollectorService(
				gcProperties,
				efsFileProcessor,
				lookupCacheService,
				logFileSource,
				statisticsCollector
		);
	}

	/**
	 * Instantiates and configures the {@link EfsFileProcessor} with its required data extractors,
	 * orphaning services, action executors registry, and statistics collector.
	 *
	 * @param lookupCacheService  the asset cache service for fast validation
	 * @param gcProperties        garbage collection runtime properties
	 * @param statisticsCollector run-scoped statistics aggregator for the consolidated end-of-run report
	 * @return a configured {@link EfsFileProcessor} instance
	 */
	private static EfsFileProcessor createEfsFileProcessor(
			AssetLookupCacheService lookupCacheService,
			EfsGarbageCollectorProperties gcProperties,
			ProcessingStatisticsCollector statisticsCollector
	) {
		AssetOrphaningService orphaningService = new AssetOrphaningService( lookupCacheService );
		PathDataExtractor dataExtractor = new PathDataExtractor();

		DeleteOrphanActionExecutor deleteExecutor = new DeleteOrphanActionExecutor();
		QuarantineOrphanActionExecutor quarantineExecutor = new QuarantineOrphanActionExecutor( gcProperties );
		WriteToRemoveFileOrphanActionExecutor writeToRemoveFileOrphanActionExecutor = new WriteToRemoveFileOrphanActionExecutor();

		List<OrphanActionExecutor> executors = List.of( deleteExecutor, quarantineExecutor,
				writeToRemoveFileOrphanActionExecutor );
		OrphanActionExecutorRegistry executorRegistry = new OrphanActionExecutorRegistry( executors );

		return new EfsFileProcessor( dataExtractor, orphaningService, executorRegistry, statisticsCollector );
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
				new CompanyRepository( dsl ),
				new AssetRepository( dsl )
		);
	}
}
