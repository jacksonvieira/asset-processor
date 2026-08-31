package pt.piscapisca.b2c.asset.processor.executor;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;
import pt.piscapisca.b2c.asset.processor.infra.EfsGarbageCollectorProperties;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Executor for {@link OrphanAction#MOVE_TO_QUARANTINE} — relocates the orphan file to a dedicated quarantine directory
 * instead of deleting it. Recommended over {@link OrphanAction#DELETE} for the first executions of the GC, until
 * confidence in the orphan-detection logic is high.
 * <p>
 * <b>Layout inside the quarantine root:</b> the source path (minus its root) is reproduced under
 * {@link EfsGarbageCollectorProperties#quarantinePath()}, preserving directory structure for forensic recovery. Given a
 * source {@code /efs/companies/ABC/logo.png} and a quarantine root of {@code /efs/quarantine}, the file is moved to
 * {@code /efs/quarantine/efs/companies/ABC/logo.png}.
 * <p>
 * <b>Collision handling:</b> if the target path already exists (e.g. previous GC run quarantined a file with the same
 * name), the incoming file is renamed with a UTC timestamp suffix — never overwrites. This preserves history.
 * <p>
 * <b>Thread-safety:</b> stateless singleton, safe under concurrent {@link EfsFileWorker} threads.
 */
@Slf4j
public class QuarantineOrphanActionExecutor implements OrphanActionExecutor {

	/**
	 * Timestamp pattern used for collision-resolution suffixes. Millisecond precision keeps collisions astronomically
	 * unlikely even in high-throughput runs; format is filename-safe (no colons, no slashes).
	 */
	private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern( "yyyyMMdd_HHmmss_SSS" );

	private static final String STATUS_MOVED_PREFIX = "MOVED_TO_QUARANTINE:";

	private static final String STATUS_FAILED_PREFIX = "FAILED_MOVE_QUARANTINE:";

	/**
	 * Cached quarantine root {@link Path}, computed once from the properties. Safe because
	 * {@link EfsGarbageCollectorProperties} is immutable.
	 */
	private final Path quarantineRoot;

	public QuarantineOrphanActionExecutor( EfsGarbageCollectorProperties properties ) {
		this.quarantineRoot = Paths.get( properties.quarantinePath() );

		try {
			Files.createDirectories( this.quarantineRoot );
		}
		catch ( IOException e ) {
			log.error(
					"Could not initialize quarantine root directory: [{}], error:  [{}]", this.quarantineRoot,
					B2CExceptionUtils.toMap( e )
			);
			throw new IllegalStateException(
					"Could not initialize quarantine root directory: " + this.quarantineRoot, e );
		}
	}

	@Override
	public OrphanAction supports() {
		return OrphanAction.MOVE_TO_QUARANTINE;
	}

	@Override
	public OrphanActionOutcome execute( Path sourceFile ) {
		try {
			// For absolute paths, strip the root so we can graft the full hierarchy under quarantineRoot without
			// losing any directory level. For relative paths (root == null) we use the path as-is.
			Path relative = sourceFile.getRoot() == null
					? sourceFile
					: sourceFile.getRoot().relativize( sourceFile );

			Path target = quarantineRoot.resolve( relative );

			// Idempotent: only creates directories that do not yet exist.
			Files.createDirectories( target.getParent() );

			// Prevents silent overwrites: if the target already exists, appends a UTC timestamp to the filename.
			Path finalTarget = resolveNonConflictingTarget( target );

			Files.move( sourceFile, finalTarget );

			log.info( "Moved orphan file to quarantine | source={} | target={}", sourceFile, finalTarget );
			return OrphanActionOutcome.success( STATUS_MOVED_PREFIX + finalTarget );
		}
		catch ( IOException e ) {
			log.warn( "Failed to move orphan file to quarantine | source={} | {}",
					sourceFile, B2CExceptionUtils.toMap( e )
			);
			return OrphanActionOutcome.failure( STATUS_FAILED_PREFIX + " " + e.getMessage() );
		}
	}

	private Path resolveNonConflictingTarget( Path target ) {
		if ( !Files.exists( target ) ) {
			return target;
		}

		String fileName = target.getFileName().toString();
		String stamp = LocalDateTime.now().format( TS_FMT );

		int dotIndex = fileName.lastIndexOf( '.' );
		String newName = ( dotIndex > 0 )
				? fileName.substring( 0, dotIndex ) + "__" + stamp + fileName.substring( dotIndex )
				: fileName + "__" + stamp;

		Path resolved = target.resolveSibling( newName );
		log.debug( "Quarantine target collision — renaming | original={} | renamed={}", target, resolved );
		return resolved;
	}
}
