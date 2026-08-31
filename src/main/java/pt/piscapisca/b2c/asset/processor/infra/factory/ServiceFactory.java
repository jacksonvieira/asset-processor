package pt.piscapisca.b2c.asset.processor.infra.factory;

import org.jooq.DSLContext;
import pt.piscapisca.b2c.asset.processor.*;
import pt.piscapisca.b2c.asset.processor.config.AppConfig;
import pt.piscapisca.b2c.asset.processor.executor.DeleteOrphanActionExecutor;
import pt.piscapisca.b2c.asset.processor.executor.OrphanActionExecutor;
import pt.piscapisca.b2c.asset.processor.executor.QuarantineOrphanActionExecutor;
import pt.piscapisca.b2c.asset.processor.executor.WriteToRemoveFileOrphanActionExecutor;
import pt.piscapisca.b2c.asset.processor.infra.EfsGarbageCollectorProperties;
import pt.piscapisca.b2c.asset.processor.infra.EfsGarbageCollectorS3Properties;
import pt.piscapisca.b2c.asset.processor.repository.*;
import pt.piscapisca.b2c.asset.processor.service.AssetLookupCacheService;
import pt.piscapisca.b2c.asset.processor.service.AssetOrphaningService;
import pt.piscapisca.b2c.asset.processor.service.EfsGarbageCollectorService;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

public class ServiceFactory {

	/**
	 * Cria e retorna o serviço principal de Garbage Collection já com todas as dependências injetadas.
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
			// Se for LOCAL, nenhum S3Client é criado ou alocado na memória
			logFileSource = new LocalLogFileSource( gcConfig.localPath() );
		}
		else {
			// Se for S3, o cliente é criado sob demanda e encapsulado na fonte
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
				efsFileProcessor,
				gcProperties,
				lookupCacheService,
				logFileSource
		);
	}

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

	private static AssetLookupCacheService createAssetLookupCacheService( DSLContext dsl ) {
		return new AssetLookupCacheService(
				new VehicleRepository( dsl ),
				new StandRepository( dsl ),
				new PersonRepository( dsl ),
				new CompanyRepository( dsl )
		);
	}
}
