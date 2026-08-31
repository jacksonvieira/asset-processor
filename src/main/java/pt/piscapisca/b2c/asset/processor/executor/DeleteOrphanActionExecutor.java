package pt.piscapisca.b2c.asset.processor.executor;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Executor for {@link OrphanAction#DELETE} — permanently removes the orphan file from EFS.
 * <p>
 * <b>Warning:</b> this operation is <em>irreversible</em>. Prefer {@code MOVE_TO_QUARANTINE} for production runs until
 * confidence in the orphan detection logic is high.
 * <p>
 * Uses {@link Files#deleteIfExists(Path)} so a race with an external actor (another cleanup process, manual deletion)
 * does not surface as an error — the file is already in the desired terminal state.
 * <p>
 * <b>Symlink note:</b> on POSIX filesystems, deleting a symlink removes the link, not its target. This is the desired
 * behaviour for EFS cleanup — the entries we are tracking <em>are</em> the links / regular files themselves.
 */
@Slf4j
public class DeleteOrphanActionExecutor implements OrphanActionExecutor {

	// Report status constants — kept close to the executor that emits them so the CSV vocabulary is discoverable.
	private static final String STATUS_DELETED = "DELETED";

	private static final String STATUS_ALREADY_ABSENT = "ALREADY_ABSENT";

	private static final String STATUS_ERROR_IO = "ERROR_IO: ";

	@Override
	public OrphanAction supports() {
		return OrphanAction.DELETE;
	}

	@Override
	public OrphanActionOutcome execute( Path file ) {
		try {
			boolean deleted = Files.deleteIfExists( file );
			if ( deleted ) {
				log.info( "Successfully deleted orphan file | path={}", file );
				return OrphanActionOutcome.success( STATUS_DELETED );
			}

			// deleted == false means the file did NOT exist at the moment of the call — NOT a failure. Typically
			// caused by a race (external process, previous partially-completed run). Report it explicitly so the CSV
			// distinguishes "we deleted it" from "it was already gone".
			log.debug( "Orphan file already absent — nothing to delete | path={}", file );
			return OrphanActionOutcome.success( STATUS_ALREADY_ABSENT );
		}
		catch ( IOException e ) {
			log.warn( "Error deleting orphan file | path={} | {}", file, B2CExceptionUtils.toMap( e ) );
			return OrphanActionOutcome.failure( STATUS_ERROR_IO + e.getMessage() );
		}
	}
}
