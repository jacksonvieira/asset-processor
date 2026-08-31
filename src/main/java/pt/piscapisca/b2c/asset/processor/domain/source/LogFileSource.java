package pt.piscapisca.b2c.asset.processor.domain.source;

import pt.piscapisca.b2c.asset.processor.domain.model.S3LogFile;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.Scope;

import java.io.BufferedReader;
import java.util.List;
import java.util.Set;

/**
 * Read-side abstraction over any log file provider (e.g., S3 bucket or local directory)
 * that holds the EFS path log files.
 */
public interface LogFileSource {

	/**
	 * Lists the log files that still need processing for the given {@code scope}, applying optional filters.
	 */
	List<S3LogFile> listPendingFiles( Scope scope, Set<String> idFilter, boolean skipAlreadyProcessed );

	/**
	 * Opens a {@link BufferedReader} that streams the contents of the file. The caller owns the reader and MUST close it.
	 */
	BufferedReader openReader( S3LogFile file );

	/**
	 * Marks the file/object as processed, recording {@code runId} and {@code orphanAction} for auditability.
	 */
	void markAsProcessed( S3LogFile file, String runId, String orphanAction );

	/**
	 * Returns {@code true} if the file has already been processed.
	 */
	boolean isProcessed( S3LogFile file );
}
