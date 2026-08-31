package pt.piscapisca.b2c.asset.processor.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.AssetDataDTO;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;
import pt.piscapisca.b2c.asset.processor.executor.OrphanActionExecutor;
import pt.piscapisca.b2c.asset.processor.executor.OrphanActionExecutorRegistry;
import pt.piscapisca.b2c.asset.processor.executor.WriteToRemoveFileOrphanActionExecutor;
import pt.piscapisca.b2c.asset.processor.parser.PathDataExtractor;
import pt.piscapisca.b2c.asset.processor.service.AssetOrphaningService;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Processes a single EFS path line coming from an S3 log file.
 * <p>
 * Responsibilities:
 * <ol>
 *   <li>Parse the path into a typed {@link AssetDataDTO} via {@link PathDataExtractor}.</li>
 *   <li>Ask {@link AssetOrphaningService} whether the asset is orphan (no matching DB row).</li>
 *   <li>If orphan, delegate the physical action (delete / quarantine) to the executor selected from
 *       {@link OrphanActionExecutorRegistry}.</li>
 * </ol>
 * <p>
 * <b>Thread-safety:</b> this class is a stateless singleton — every field it holds is either a stateless service or
 * a thread-safe collaborator. Multiple {@link EfsFileWorker} threads may safely call
 * {@link #process(String, String, String, boolean, OrphanAction)} concurrently.
 * <p>
 * <b>Dry-run:</b> in dry-run mode no filesystem change is made and no executor is invoked. Instead, an entry with a
 * {@code WOULD_*} status is written to the report so operators can review what would happen before committing.
 * <p>
 * <b>Error policy:</b> exceptions are caught and translated into report entries — a single bad path never aborts the
 * worker.
 */
@Slf4j
@RequiredArgsConstructor
public class EfsFileProcessor {

	private final PathDataExtractor dataExtractor;

	private final AssetOrphaningService orphaningService;

	private final OrphanActionExecutorRegistry executorRegistry;

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
			// Step 1 + 2: parse + orphan check
			if ( !isOrphanCandidate( normalizedPath ) ) {
				return false;
			}

			if ( dryRun ) {
				log.info( "[DRY-RUN] Would apply action on orphan path | action={} | path={}", orphanAction,
						normalizedPath );
				return true;
			}

			// Step 3: apply the specific action
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
				log.info( "Successfully applied action on orphan file | action={} | path={} | status={}",
						orphanAction, normalizedPath, outcome.reportStatus()
				);
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
	 * Extracts the asset descriptor from the path and asks the orphaning service whether it is a candidate for action
	 * (i.e. it has no matching row in the DB).
	 */
	private boolean isOrphanCandidate( String pathStr ) {
		AssetDataDTO potentialAssetToRemove = dataExtractor.extractData( pathStr );

		if ( potentialAssetToRemove == null ) {
			log.warn( "Path not recognised by any known pattern — skipping | path={}", pathStr );
			return false;
		}

		log.trace( "Extracted asset data successfully | path={} | asset={}", pathStr, potentialAssetToRemove );

		boolean orphan = orphaningService.isOrphan( potentialAssetToRemove );
		if ( !orphan ) {
			log.trace( "Asset still referenced in DB — keeping file | path={}", pathStr );
		}
		return orphan;
	}
}
