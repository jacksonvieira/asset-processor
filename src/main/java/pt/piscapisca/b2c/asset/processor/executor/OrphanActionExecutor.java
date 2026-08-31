package pt.piscapisca.b2c.asset.processor.executor;

import pt.piscapisca.b2c.asset.processor.execution.EfsFileWorker;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;

import java.nio.file.Path;

/**
 * Strategy that applies a concrete physical action (delete, move to quarantine, ...) to a file that has been classified
 * as orphan.
 * <p>
 * <b>Contract:</b>
 * <ul>
 *   <li>Every implementation MUST be idempotent — re-running the same action on the same file must not corrupt
 *       state (e.g. deleting an already-deleted file must succeed as a no-op).</li>
 *   <li>Implementations MUST NOT throw for expected filesystem states (missing file, target already exists, ...);
 *       they must translate them into a well-typed {@link OrphanActionOutcome}.</li>
 *   <li>Implementations MUST be thread-safe: multiple {@link EfsFileWorker} threads may invoke {@link #execute(Path)}
 *       concurrently on distinct files.</li>
 * </ul>
 * <p>
 * Selection is done at runtime via {@link OrphanActionExecutorRegistry#get(OrphanAction)} based on
 * {@link #supports()}.
 */
public interface OrphanActionExecutor {

	/**
	 * @return the {@link OrphanAction} value this executor handles. The registry uses this to build its dispatch map,
	 * so every executor bean MUST return a distinct value.
	 */
	OrphanAction supports();

	/**
	 * Applies the action to the given file.
	 *
	 * @param file absolute path of the orphan file — the caller has already validated the path syntactically
	 * @return outcome describing what happened; used verbatim to populate the CSV report
	 */
	OrphanActionOutcome execute( Path file );

	/**
	 * Result of an {@link #execute(Path)} call.
	 *
	 * @param success      whether the action was applied successfully (or the file was already in the desired terminal
	 *                     state, e.g. already deleted — those count as {@code true})
	 * @param reportStatus short machine-readable status string written to the CSV report; conventionally
	 *                     {@code UPPER_SNAKE_CASE}
	 */
	record OrphanActionOutcome(boolean success, String reportStatus) {

		public static OrphanActionOutcome success( String status ) {
			return new OrphanActionOutcome( true, status );
		}

		public static OrphanActionOutcome failure( String status ) {
			return new OrphanActionOutcome( false, status );
		}
	}
}

