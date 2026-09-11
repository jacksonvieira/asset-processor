package pt.piscapisca.b2c.asset.processor.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.AssetDataDTO;
import pt.piscapisca.b2c.asset.processor.dto.AssetGarbageCollectorType;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;
import pt.piscapisca.b2c.asset.processor.executor.OrphanActionExecutor;
import pt.piscapisca.b2c.asset.processor.executor.OrphanActionExecutorRegistry;
import pt.piscapisca.b2c.asset.processor.executor.WriteToRemoveFileOrphanActionExecutor;
import pt.piscapisca.b2c.asset.processor.infra.statistics.ProcessingStatisticsCollector;
import pt.piscapisca.b2c.asset.processor.parser.PathDataExtractor;
import pt.piscapisca.b2c.asset.processor.service.AssetOrphaningService;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Processes a single EFS path line coming from an S3/Local log file.
 * <p>
 * Responsibilities:
 * <ol>
 *   <li>Parse the path into a typed {@link AssetDataDTO} via {@link PathDataExtractor}.</li>
 *   <li>Ask {@link AssetOrphaningService} whether the asset is orphan (no matching DB row).</li>
 *   <li>If orphan, delegate the physical action (delete / quarantine / write-to-remove) to the
 *       executor selected from {@link OrphanActionExecutorRegistry}.</li>
 *   <li>Record the outcome in {@link ProcessingStatisticsCollector} for the consolidated
 *       end-of-run report.</li>
 * </ol>
 * <p>
 * <b>Thread-safety:</b> every collaborator held by this class is either stateless or internally
 * thread-safe. {@link ProcessingStatisticsCollector} uses {@link java.util.concurrent.ConcurrentHashMap}
 * + {@link java.util.concurrent.atomic.LongAdder} and is safe for concurrent writes.
 * Multiple {@link EfsFileWorker} threads may safely call
 * {@link #process(String, String, String, boolean, OrphanAction)} concurrently.
 * <p>
 * <b>Dry-run:</b> in dry-run mode no filesystem change is made and no executor is invoked. Instead,
 * a log entry is produced and the asset is counted as "would be removed" in the consolidated report.
 * <p>
 * <b>Error policy:</b> exceptions are caught so that a single bad path never aborts the worker.
 */
@Slf4j
@RequiredArgsConstructor
public class EfsFileProcessor implements AutoCloseable {

	private final PathDataExtractor dataExtractor;

	private final AssetOrphaningService orphaningService;

	private final OrphanActionExecutorRegistry executorRegistry;

	private final ProcessingStatisticsCollector statisticsCollector;

	/**
	 * Processes a single EFS path line.
	 *
	 * @param rawLine        the exact raw line read from the log file
	 * @param normalizedPath normalized path extracted for DB/orphan checks
	 * @param sourceId       identifier or path of the log file being read (e.g., local file path or S3 key)
	 * @param dryRun         if {@code true}, no side effect is applied
	 * @param orphanAction   action to apply when confirmed orphan
	 * @return {@code true} if an action was applied (or would be applied in dry-run)
	 */
	public boolean process( String rawLine, String normalizedPath, String sourceId, boolean dryRun,
			DevAssetGarbageCollectionCommand.OrphanAction orphanAction ) {
		log.trace( "Processing path line | normalizedPath={} | sourceId={} | dryRun={} | orphanAction={}",
				normalizedPath, sourceId, dryRun, orphanAction );
		try {
			// Step 1: parse path into typed asset descriptor
			AssetDataDTO asset = dataExtractor.extractData( normalizedPath );
			if ( asset == null ) {
				log.warn( "Path not recognised by any known pattern — skipping | path={}", normalizedPath );
				return false;
			}

			log.trace( "Extracted asset data successfully | path={} | asset={}", normalizedPath, asset );

			// Register path as analysed — happens for every successfully parsed asset
			String entityKey = buildEntityKey( asset );
			String entityLabel = buildEntityLabel( asset );
			String entityType = buildEntityType( asset );
			statisticsCollector.recordAnalyzed( entityKey, entityLabel, entityType );

			// Step 2: orphan check
			boolean orphan = orphaningService.isOrphan( asset );
			if ( !orphan ) {
				log.trace( "Asset still referenced in DB — keeping file | path={}", normalizedPath );
				statisticsCollector.recordToKeep( entityKey, entityLabel, entityType );
				return false;
			}

			// Step 3: dry-run — no side effects, but count as "would remove" for the report
			if ( dryRun ) {
				log.debug( "[DRY-RUN] Would apply action on orphan path | action={} | path={}", orphanAction,
						normalizedPath );
				statisticsCollector.recordToRemove( entityKey, entityLabel, entityType );
				return true;
			}

			// Step 4: apply the specific action
			OrphanActionExecutor.OrphanActionOutcome outcome;

			if ( orphanAction == DevAssetGarbageCollectionCommand.OrphanAction.WRITE_TO_REMOVE_FILE ) {
				WriteToRemoveFileOrphanActionExecutor removeExecutor =
						(WriteToRemoveFileOrphanActionExecutor) executorRegistry.get( orphanAction );

				Path originalLogPath = Paths.get( sourceId );
				outcome = removeExecutor.executeForLogLine( rawLine, originalLogPath );
			}
			else {
				Path file = Paths.get( normalizedPath );
				OrphanActionExecutor executor = executorRegistry.get( orphanAction );
				outcome = executor.execute( file );
			}

			if ( outcome.success() ) {
				log.debug( "Successfully applied action on orphan file | action={} | path={} | status={}",
						orphanAction, normalizedPath, outcome.reportStatus()
				);
				statisticsCollector.recordToRemove( entityKey, entityLabel, entityType );
			}
			else {
				log.warn( "Executor reported failure for orphan file | action={} | path={} | status={}",
						orphanAction, normalizedPath, outcome.reportStatus()
				);
			}
			return outcome.success();
		}
		catch ( Exception e ) {
			// Catch-all: a single bad line must never abort the worker. Reported so operators can inspect later.
			log.error( "Unexpected error while processing path line | path={} | sourceId={} | {}",
					normalizedPath, sourceId, B2CExceptionUtils.toMap( e )
			);
			return false;
		}
	}

	/**
	 * Builds a stable unique map key for the statistics collector.
	 * Format: {@code "COMPANY_<id>"} or {@code "PERSON_<id>"}.
	 * Falls back to {@code "UNKNOWN"} when the asset carries neither ID (should not occur in practice).
	 */
	private String buildEntityKey( AssetDataDTO asset ) {
		if ( asset.hasCompanyId() ) {
			return "COMPANY_" + asset.getCompanyId();
		}
		if ( asset.getPersonId() != null ) {
			return "PERSON_" + asset.getPersonId();
		}
		return "UNKNOWN";
	}

	/**
	 * Builds a human-readable label used in log lines.
	 * Examples: {@code "Company 12345"}, {@code "Person 67890"}.
	 */
	private String buildEntityLabel( AssetDataDTO asset ) {
		if ( asset.hasCompanyId() ) {
			return "Company " + asset.getCompanyId();
		}
		if ( asset.getPersonId() != null ) {
			return "Person " + asset.getPersonId();
		}
		return "Unknown entity";
	}

	/**
	 * Returns the entity type string used to group entries in the consolidated report.
	 * Maps directly to {@link AssetGarbageCollectorType#name()} — {@code "COMPANY"} or {@code "PERSON"}.
	 */
	private String buildEntityType( AssetDataDTO asset ) {
		AssetGarbageCollectorType type = asset.getAssetType();
		return ( type != null && type != AssetGarbageCollectorType.UNKNOWN ) ? type.name() : "UNKNOWN";
	}

	/**
	 * Releases the {@code *_to_remove.txt} writer associated with a finished source file (WRITE_TO_REMOVE mode only).
	 * No-op for other actions. Frees the file handle promptly so long runs do not accumulate open descriptors.
	 */
	public void closeRemoveWriterFor( String sourceId ) {
		if ( sourceId == null ) {
			return;
		}
		OrphanActionExecutor exec = executorRegistry.find( OrphanAction.WRITE_TO_REMOVE_FILE );
		if ( exec instanceof WriteToRemoveFileOrphanActionExecutor removeExecutor ) {
			removeExecutor.closeWriterFor( Paths.get( sourceId ) );
		}
	}

	/**
	 * Closes any resource-holding executors at the end of a run.
	 */
	@Override
	public void close() {
		executorRegistry.close();
	}
}
