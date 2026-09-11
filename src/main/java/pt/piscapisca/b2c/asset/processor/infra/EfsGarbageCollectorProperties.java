package pt.piscapisca.b2c.asset.processor.infra;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import pt.piscapisca.b2c.asset.processor.execution.EfsFileWorker;

/**
 * Configuration for the EFS asset garbage collector.
 *
 * @param quarantinePath       absolute filesystem path where orphan files are moved when
 *                             {@code OrphanAction.MOVE_TO_QUARANTINE} is used. Must exist (or be creatable) and be
 *                             writable by the JVM process. Trailing slashes are tolerated.
 * @param defaultParallelFiles default number of S3 log files processed concurrently when
 *                             {@code ProcessingMode.PARALLEL_FILES} is selected and the caller does not override it.
 *                             Should be {@code <= maxParallelFiles}.
 * @param maxParallelFiles     hard cap for parallel file processing. Caller-provided values above this are clamped down
 *                             (see {@code EfsGarbageCollectorService#clampParallelFiles}). Protects the DB connection
 *                             pool from oversubscription.
 * @param workerThreads        number of threads inside a single {@link EfsFileWorker} used to process the individual
 *                             path lines of one S3 file. Total in-flight threads in parallel mode is approximately
 *                             {@code parallelFiles * workerThreads} — size the DB pool accordingly.
 * @param maximumPoolSize      the HikariCP {@code database.maximum-pool-size}. Used to fail fast at startup if the
 *                             per-file {@code workerThreads} alone would exceed the DB connection pool, which would
 *                             otherwise surface at runtime as connection timeouts wrongly classified as critical.
 */
public record EfsGarbageCollectorProperties(

		@NotBlank
		String quarantinePath,

		@Min( 1 )
		int defaultParallelFiles,

		@Min( 1 )
		int maxParallelFiles,

		@Min( 1 )
		int workerThreads,

		@Min( 1 )
		int maximumPoolSize

) {

	/**
	 * Cross-field validation. Runs after the per-field annotations.
	 * <p>
	 * Fails the application context boot if the parallelism configuration is internally inconsistent — better to fail
	 * loud at startup than to silently clamp values at runtime and confuse operators.
	 */
	public EfsGarbageCollectorProperties {
		if ( defaultParallelFiles > maxParallelFiles ) {
			throw new IllegalArgumentException(
					"b2c.companies.efs.garbage-collector.defaultParallelFiles (" + defaultParallelFiles
							+ ") must be <= maxParallelFiles (" + maxParallelFiles + ")" );
		}
		if ( workerThreads > maximumPoolSize ) {
			throw new IllegalArgumentException(
					"b2c.companies.efs.garbage-collector.workerThreads (" + workerThreads
							+ ") must be <= database.maximum-pool-size (" + maximumPoolSize
							+ ") to avoid exhausting the DB connection pool" );
		}
	}
}

