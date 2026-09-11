package pt.piscapisca.b2c.asset.processor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import pt.piscapisca.b2c.asset.processor.domain.model.S3LogFile;
import pt.piscapisca.b2c.asset.processor.domain.source.LogFileSource;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand;
import pt.piscapisca.b2c.asset.processor.execution.EfsFileProcessor;
import pt.piscapisca.b2c.asset.processor.execution.EfsFileWorker;
import pt.piscapisca.b2c.asset.processor.execution.EfsPathFilter;
import pt.piscapisca.b2c.asset.processor.infra.EfsGarbageCollectorProperties;
import pt.piscapisca.b2c.asset.processor.infra.checkpoint.FileCheckpointManager;
import pt.piscapisca.b2c.asset.processor.infra.statistics.ProcessingStatisticsCollector;
import pt.piscapisca.b2c.hashids.SecretId;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Orchestrates the EFS asset garbage collection process.
 * <p>
 * Reads log files from S3/Local (produced by DevOps), each containing a list of EFS paths belonging to a company or person.
 * For every path, delegates to {@link EfsFileWorker} which checks whether the underlying asset is orphan (no matching
 * DB entry) and — if so — applies the requested {@link DevAssetGarbageCollectionCommand.OrphanAction}
 * (MOVE_TO_QUARANTINE or DELETE).
 * <p>
 * <b>Concurrency model:</b>
 * <ul>
 *   <li>{@link #run(DevAssetGarbageCollectionCommand)} is {@code @Async} — HTTP callers get an immediate response.</li>
 *   <li>A single-run mutex ({@link #running}) rejects overlapping executions.</li>
 *   <li>{@code SEQUENTIAL} mode processes one S3/Local file at a time; {@code PARALLEL_FILES} spawns up to
 *       {@code parallelFiles} concurrent workers.</li>
 * </ul>
 * <b>Idempotency:</b> S3/Local objects successfully processed are tagged (see
 * {@link LogFileSource#markAsProcessed}); subsequent runs skip them by default. Dry-run executions never tag,
 * allowing safe re-execution.
 */
@Slf4j
@RequiredArgsConstructor
public class EfsGarbageCollectorService implements AutoCloseable {

	private final EfsGarbageCollectorProperties gcProperties;

	private final EfsFileProcessor processor;

	private final AssetLookupCacheService assetLookupCacheService;

	private final LogFileSource logFileSource;

	private final ProcessingStatisticsCollector statisticsCollector;

	/**
	 * Single-run mutex.
	 * <p>
	 * Protects shared mutable resources that cannot handle concurrent GC runs safely: the DB connection pool, the
	 * quarantine directory, the report writer, and the S3/Local tagging state. If a run is already in progress, the caller's
	 */
	private final AtomicBoolean running = new AtomicBoolean( false );

	/**
	 * Hard cap for {@link ExecutorService#awaitTermination(long, TimeUnit)} calls.
	 * <p>
	 * Applies both to the per-file worker executor and to the parallel-files coordinator pool. Kept as a constant
	 * because 60 minutes is generous enough for any realistic batch — if it ever needs tuning, promote to a property.
	 */
	private static final int EXECUTOR_TIMEOUT_MINUTES = 60;

	/**
	 * Entry point for a garbage collection run.
	 * <p>
	 * Runs asynchronously — the caller's HTTP request returns immediately (202 Accepted). Progress must be tracked
	 * through logs and Micrometer metrics.
	 * <p>
	 * Only one run is allowed at a time; concurrent invocations are rejected and logged.
	 *
	 * @param command execution parameters — never {@code null}; must have been validated upstream
	 */
	public void run( DevAssetGarbageCollectionCommand command ) {
		if ( !running.compareAndSet( false, true ) ) {
			log.warn( "EFS GC already running, request ignored | runId=N/A" );
			return;
		}

		String runId = UUID.randomUUID().toString();
		MDC.put( "runId", runId );
		Instant runStart = Instant.now();

		log.info( "Starting EFS GC | runId={} | scope={} | mode={} | dryRun={} | orphanAction={} "
						+ "| idFilterSize={} | skipAlreadyProcessed={} | parallelFiles={}",
				runId, command.scope(), command.mode(), command.dryRun(), command.orphanAction(),
				command.ids().size(), command.skipAlreadyProcessed(), command.parallelFiles()
		);

		try {
			Set<String> idFilter = command.ids().stream().map( SecretId::loadSecretId )
					.collect( Collectors.toUnmodifiableSet() );

			List<S3LogFile> pending = logFileSource.listPendingFiles(
					command.scope(), idFilter, command.skipAlreadyProcessed() );

			if ( pending.isEmpty() ) {
				log.info( "No pending S3/Local log files match the given filters, nothing to do | runId={}", runId );
				return;
			}

			assetLookupCacheService.clearLookupCaches();
			// Reset per-entity counters so this run starts clean (no residual state from a prior run)
			statisticsCollector.reset();
			try {
				switch ( command.mode() ) {
				case SEQUENTIAL -> processSequential( pending, command, runId );
				case PARALLEL_FILES -> processParallel( pending, command, runId );
				}
			}
			finally {
				// Emit the consolidated per-entity report whether the run succeeded or failed,
				// so partial progress is always visible.
				statisticsCollector.logConsolidatedReport( runId, command.dryRun() );
				log.debug( "Finalizing report and clearing caches | runId={}", runId );
				assetLookupCacheService.clearLookupCaches();
			}

			log.info(
					"EFS GC completed | runId={} | duration={} | filesProcessed={}", runId,
					Duration.between( runStart, Instant.now() ), pending.size()
			);
		}
		catch ( Exception e ) {
			log.error(
					"EFS GC failed | runId={} | duration={} | error={}", runId,
					Duration.between( runStart, Instant.now() ),
					B2CExceptionUtils.toMap( e )
			);
		}
		finally {
			running.set( false );
			MDC.remove( "runId" );
			log.debug( "Run mutex released | runId={}", runId );
		}
	}

	/**
	 * Processes files one at a time. All worker threads focus on the current file before moving to the next.
	 */
	private void processSequential( List<S3LogFile> files, DevAssetGarbageCollectionCommand command, String runId ) {
		log.debug( "Sequential mode | runId={} | filesToProcess={}", runId, files.size() );
		int index = 0;
		for ( S3LogFile file : files ) {
			index++;
			log.debug( "Sequential progress | runId={} | fileIndex={} | totalFiles={} | key={}", runId, index,
					files.size(), file.key() );
			processSingleFile( file, command, runId );
		}
	}

	/**
	 * Processes {@code parallelFiles} files concurrently, each with its own {@link EfsFileWorker}.
	 */
	private void processParallel( List<S3LogFile> files, DevAssetGarbageCollectionCommand command, String runId ) {
		int parallelFiles = clampParallelFiles( command.parallelFiles() );
		int totalInFlightApprox = parallelFiles * gcProperties.workerThreads();
		log.info( "Parallel mode | runId={} | filesToProcess={} | parallelFiles={} | workerThreadsPerFile={} "
						+ "| totalInFlightThreadsApprox={}",
				runId, files.size(), parallelFiles, gcProperties.workerThreads(),
				totalInFlightApprox
		);

		if ( totalInFlightApprox > gcProperties.maximumPoolSize() ) {
			log.warn( "Parallel configuration may oversubscribe the DB connection pool — DB calls will queue/timeout "
							+ "| parallelFiles={} | workerThreadsPerFile={} | totalInFlightApprox={} | maximumPoolSize={}",
					parallelFiles, gcProperties.workerThreads(), totalInFlightApprox, gcProperties.maximumPoolSize()
			);
		}

		ExecutorService pool = Executors.newFixedThreadPool(
				parallelFiles,
				Thread.ofPlatform().name( "efs-gc-file-", 0 ).factory()
		);

		try {
			List<CompletableFuture<Void>> futures = files.stream()
					.map( f -> CompletableFuture.runAsync( () -> processSingleFile( f, command, runId ), pool ) )
					.toList();

			// Block until every file finishes (either normally or exceptionally — exceptions are caught inside
			// processSingleFile so this join() shouldn't throw in practice).
			CompletableFuture.allOf( futures.toArray( CompletableFuture[]::new ) ).join();
			log.debug( "All parallel file tasks completed | runId={}", runId );
		}
		finally {
			pool.shutdown();
			try {
				if ( !pool.awaitTermination( EXECUTOR_TIMEOUT_MINUTES, TimeUnit.MINUTES ) ) {
					log.error(
							"Parallel file pool did not terminate within timeout, forcing shutdownNow | runId={} | timeoutMinutes={}",
							EXECUTOR_TIMEOUT_MINUTES, runId
					);
					pool.shutdownNow();
				}
				else {
					log.debug( "Parallel file pool terminated cleanly | runId={}", runId );
				}
			}
			catch ( InterruptedException e ) {
				log.warn( "Interrupted while waiting for parallel file pool termination | runId={}", runId );
				Thread.currentThread().interrupt();
				pool.shutdownNow();
			}
		}
	}

	/**
	 * Processes a single S3/Local log file end-to-end: handles checkpoint recovery, delegates lines to {@link EfsFileWorker},
	 * and (unless dry-run) tags the object as processed for future idempotency.
	 */
	private void processSingleFile( S3LogFile file, DevAssetGarbageCollectionCommand command, String runId ) {
		log.debug( "Processing S3/Local file | runId={} | key={} | entityId={} | sizeBytes={}",
				runId, file.key(), file.entityId(), file.sizeBytes()
		);

		Instant fileStart = Instant.now();
		String fileKey = file.key();

		// Lê o checkpoint usando a chave unificada (funciona igual para S3 e Local)
		long lastProcessedLine = FileCheckpointManager.readLastProcessedLine( fileKey );
		logCheckpointResume( fileKey, lastProcessedLine );

		EfsFileWorker worker = createWorker( command );
		worker.start( fileKey );

		try ( BufferedReader reader = logFileSource.openReader( file ) ) {
			processFileLines( reader, worker, fileKey, lastProcessedLine, fileKey );

			worker.shutdown();
			finalizeFileProcessing( file, command, runId, fileKey );

			log.debug( "Finished S3/Local file | runId={} | key={} | duration={}",
					runId, file.key(), Duration.between( fileStart, Instant.now() )
			);
		}
		catch ( Exception e ) {
			worker.shutdown();
			log.error( "Error processing S3/Local file | runId={} | key={} | duration={} | error={}",
					runId, file.key(), Duration.between( fileStart, Instant.now() ), B2CExceptionUtils.toMap( e )
			);
		}
		finally {
			// Release the WRITE_TO_REMOVE file handle for this source file (no-op for other actions).
			processor.closeRemoveWriterFor( fileKey );
		}
	}

	private EfsFileWorker createWorker( DevAssetGarbageCollectionCommand command ) {
		return new EfsFileWorker(
				gcProperties.workerThreads(),
				EfsPathFilter.defaultFilter(),
				processor,
				command.dryRun(),
				command.orphanAction(),
				EXECUTOR_TIMEOUT_MINUTES
		);
	}

	private void logCheckpointResume( String fileKey, long lastProcessedLine ) {
		if ( lastProcessedLine > 0 ) {
			log.info( "Resuming file processing from checkpoint | fileKey={} | skippingLines={}",
					fileKey, lastProcessedLine );
		}
	}

	private void processFileLines( BufferedReader reader, EfsFileWorker worker, String fileKey,
			long lastProcessedLine, String sourceId ) throws IOException {

		long currentLineNumber = 0;
		long batchCounter = 0;

		while ( currentLineNumber < lastProcessedLine && reader.readLine() != null ) {
			currentLineNumber++;
		}

		String line;
		while ( ( line = reader.readLine() ) != null ) {
			currentLineNumber++;

			try {
				worker.processSingleLine( line, sourceId );
			}
			catch ( Exception e ) {
				if ( isCriticalInfrastructureError( e ) ) {
					log.error(
							"Critical infrastructure or database error detected, aborting execution to prevent false positives | error={}",
							e.getMessage() );
					if ( e instanceof RuntimeException re ) {
						throw re;
					}
					throw new RuntimeException( "Critical processing error", e );
				}

				log.warn( "Skipping line due to non-critical error | lineNumber={} | error={}", currentLineNumber,
						e.getMessage() );
			}

			if ( ++batchCounter >= 1000 ) {
				// Ensure every line submitted so far has actually been processed before persisting the
				// checkpoint, otherwise a resume could skip orphans still in flight at checkpoint time.
				worker.awaitInFlightCompletion();
				FileCheckpointManager.saveCheckpoint( fileKey, currentLineNumber );
				batchCounter = 0;
			}
		}

		// Drain remaining in-flight tasks before the final checkpoint of this file.
		worker.awaitInFlightCompletion();
		FileCheckpointManager.saveCheckpoint( fileKey, currentLineNumber );
	}

	/**
	 * Detects infrastructure/database failures that must abort the whole run instead of being logged and skipped.
	 * <p>
	 * Uses exception <b>type</b> matching (walking the cause chain) rather than fragile substring matching on class
	 * names and messages. {@link SQLException} covers HikariCP pool exhaustion / connection failures
	 * (e.g. {@code SQLTransientConnectionException}); {@link ConnectException} / {@link SocketTimeoutException} /
	 * {@link TimeoutException} cover network-level failures reaching the database or S3.
	 */
	private boolean isCriticalInfrastructureError( Throwable e ) {
		Throwable cause = e;
		while ( cause != null ) {
			if ( cause instanceof SQLException
					|| cause instanceof ConnectException
					|| cause instanceof SocketTimeoutException
					|| cause instanceof TimeoutException ) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	private void finalizeFileProcessing( S3LogFile file, DevAssetGarbageCollectionCommand command,
			String runId, String fileKey ) {

		if ( command.dryRun() ) {
			log.info( "[DRY-RUN] Skipping markAsProcessed | runId={} | key={}", runId, file.key() );
		}
		else {
			log.debug( "Tagging S3/Local object as processed | runId={} | key={}", runId, file.key() );
			logFileSource.markAsProcessed( file, runId, command.orphanAction().name() );

			// Limpa o arquivo de estado local quando o arquivo for 100% concluído
			FileCheckpointManager.clearCheckpoint( fileKey );
		}
	}

	/**
	 * Resolves the effective parallelism for {@code PARALLEL_FILES} mode.
	 * <ul>
	 *   <li>If the caller did not specify a value (or specified &le; 0) → fall back to
	 *       {@link EfsGarbageCollectorProperties#defaultParallelFiles()}.</li>
	 *   <li>The result is always clamped by {@link EfsGarbageCollectorProperties#maxParallelFiles()} to prevent
	 *       abusive requests from oversubscribing the DB pool.</li>
	 * </ul>
	 */
	private int clampParallelFiles( Integer requested ) {
		int fallback = gcProperties.defaultParallelFiles();
		int max = gcProperties.maxParallelFiles();
		int value = ( requested == null || requested <= 0 ) ? fallback : requested;
		int clamped = Math.min( value, max );

		if ( requested != null && requested > max ) {
			log.warn( "Requested parallelFiles exceeds max, clamping | requested={} | max={} | clamped={}", requested,
					max, clamped );
		}
		return clamped;
	}

	@Override public void close() throws Exception {
		processor.close();
		if ( logFileSource instanceof AutoCloseable closeable ) {
			closeable.close();
		}
	}
}
