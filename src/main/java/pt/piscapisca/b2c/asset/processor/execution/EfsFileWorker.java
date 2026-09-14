package pt.piscapisca.b2c.asset.processor.execution;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class EfsFileWorker {

	/**
	 * Executor backed by one virtual thread per task (Java 21). Virtual threads are extremely cheap, so the number of
	 * live tasks is bounded by {@link #permits} rather than by a fixed platform-thread pool. This keeps the memory
	 * footprint tiny on the resource-limited bastion while still allowing high I/O concurrency.
	 */
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/**
	 * Bounds the number of tasks that can be in flight at any moment. A permit is acquired before a line is submitted
	 * (providing natural back-pressure on the reader thread) and released when the task finishes. The bound is sized to
	 * stay within the DB connection pool so DB access never oversubscribes the pool.
	 */
	private final Semaphore permits;

	private final int concurrency;

	private final EfsPathFilter filter;

	private final EfsFileProcessor processor;

	private final boolean dryRun;

	private final OrphanAction orphanAction;

	private final int executorTimeoutMinutes;

	private final AtomicBoolean isShutdown = new AtomicBoolean( false );

	private final AtomicLong processedCount = new AtomicLong();

	private final AtomicLong deletedCount = new AtomicLong();

	private final AtomicLong processedBytes = new AtomicLong();

	private final AtomicLong deletedBytes = new AtomicLong();

	private Instant startTime;

	private String currentSourceId;

	public EfsFileWorker( int threads,
			EfsPathFilter filter,
			EfsFileProcessor processor,
			boolean dryRun,
			OrphanAction orphanAction,
			int executorTimeoutMinutes ) {

		this.concurrency = Math.max( 1, threads );
		this.permits = new Semaphore( this.concurrency );

		this.filter = filter;
		this.processor = processor;
		this.dryRun = dryRun;
		this.orphanAction = orphanAction;
		this.executorTimeoutMinutes = executorTimeoutMinutes;

	}

	public void start( String sourceId ) {
		this.startTime = Instant.now();
		this.currentSourceId = sourceId;
		this.isShutdown.set( false );
		log.info( "Starting processing worker for source | sourceId={} | dryRun={} | action={} | concurrency={}",
				sourceId, dryRun, orphanAction, concurrency );
	}

	public void processSingleLine( String line, String sourceId ) {
		if ( line == null ) {
			return;
		}

		String trimmed = line.trim();
		if ( !trimmed.isEmpty() && filter.shouldProcess( trimmed ) ) {
			String normalizedPath = normalizePath( trimmed );
			submitTask( line, normalizedPath, sourceId );
		}
	}

	private void submitTask( String rawLine, String normalizedPath, String sourceId ) {
		Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

		// Back-pressure: block the reader thread until a permit is free, so at most `concurrency` tasks run at once.
		try {
			permits.acquire();
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			log.error( "Interrupted while acquiring worker permit | path={}", normalizedPath );
			return;
		}

		try {
			executor.submit( createProcessingTask( rawLine, normalizedPath, sourceId, mdcSnapshot ) );
		}
		catch ( RejectedExecutionException ex ) {
			permits.release();
			log.error( "Task rejected (executor shutting down) | path={}", normalizedPath );
		}
		catch ( Exception e ) {
			permits.release();
			log.error( "Unexpected error submitting task | path={} | {}", normalizedPath, B2CExceptionUtils.toMap( e ) );
		}
	}

	private Runnable createProcessingTask( String rawLine, String normalizedPath, String sourceId,
			Map<String, String> mdcSnapshot ) {
		return () -> {
			try {
				if ( mdcSnapshot != null ) {
					MDC.setContextMap( mdcSnapshot );
				}
				boolean actionApplied = processor.process( rawLine, normalizedPath, sourceId, dryRun, orphanAction );
				long fileSize = 0L;
				updateCounters( actionApplied, fileSize );
			}
			catch ( Exception e ) {
				log.error( "Error executing processing task for file | path={} | {}", normalizedPath, B2CExceptionUtils.toMap( e ) );
			}
			finally {
				MDC.clear();
				permits.release();
			}
		};
	}

	private String normalizePath( String rawPath ) {
		if ( rawPath == null || rawPath.isBlank() ) {
			return rawPath;
		}

		int imagesIndex = rawPath.indexOf( "/images" );

		if ( imagesIndex != -1 ) {
			return rawPath.substring( imagesIndex );
		}

		return rawPath;
	}

	private void updateCounters( boolean actionApplied, long fileSize ) {
		processedBytes.addAndGet( fileSize );

		if ( actionApplied ) {
			deletedBytes.addAndGet( fileSize );

			long count = deletedCount.incrementAndGet();
			if ( dryRun && count % 100000 == 0 ) {
				log.info( "[DRY-RUN] Files matched for action so far | count={} | sourceId={}", count, currentSourceId );
			}
		}

		long count = processedCount.incrementAndGet();
		if ( count % 100000 == 0 ) {
			log.debug( "Processing progress update | sourceId={} | processedFiles={} | inFlight={}",
					currentSourceId, count, ( concurrency - permits.availablePermits() )
			);
		}
	}

	/**
	 * Blocks until every task submitted so far has finished executing.
	 * <p>
	 * Implemented by draining all permits (which are only released when a task completes) and then restoring them.
	 * Callers use this to guarantee that a checkpoint reflects lines actually <b>completed</b>, not merely submitted —
	 * preventing a resume from skipping orphans that were still being processed when the checkpoint was written.
	 */
	public void awaitInFlightCompletion() {
		try {
			permits.acquire( concurrency );
			permits.release( concurrency );
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			log.warn( "Interrupted while waiting for in-flight tasks to complete | sourceId={}", currentSourceId );
		}
	}

	public void shutdown() {
		if ( isShutdown.getAndSet( true ) ) {
			return;
		}

		log.info( "Shutting down executor for source | sourceId={}", currentSourceId );
		executor.shutdown();
		try {
			if ( !executor.awaitTermination( executorTimeoutMinutes, TimeUnit.MINUTES ) ) {
				log.error( "Timeout! Not all tasks finished within limit | timeoutMinutes={} | sourceId={}",
						executorTimeoutMinutes, currentSourceId
				);
			}
			logFinalStats();
		}
		catch ( InterruptedException e ) {
			log.error( "Interrupted during executor shutdown | {}", B2CExceptionUtils.toMap( e ) );
			Thread.currentThread().interrupt();
		}
	}

	private void logFinalStats() {
		if ( startTime == null )
			return;

		Instant end = Instant.now();
		Duration duration = Duration.between( startTime, end );

		long totalProcessed = processedCount.get();
		long totalActioned = deletedCount.get();

		double processedGigaBytes = processedBytes.get() / ( 1024.0 * 1024.0 * 1024.0 );
		double actionedGigaBytes = deletedBytes.get() / ( 1024.0 * 1024.0 * 1024.0 );

		String elapsed = String.format( "%02dh %02dm %02ds",
				duration.toHoursPart(), duration.toMinutesPart(), duration.toSecondsPart()
		);

		double minutes = Math.max( 1, duration.toMinutes() );
		double rate = totalProcessed / minutes;

		String actionLabel = dryRun ? "Would " + orphanAction.name() : orphanAction.name();

		String finalMessage = String.format(
				"Source [%s] completed. [Time: %s, Processed Files: %,d, Processed Volume: ~%.2f GB, %s: %,d files, Volume %s: ~%.2f GB, Rate: ~%d files/min]",
				currentSourceId,
				elapsed,
				totalProcessed,
				processedGigaBytes,
				actionLabel,
				totalActioned,
				actionLabel,
				actionedGigaBytes,
				Math.round( rate )
		);

		log.info( "{}", finalMessage );
	}
}
