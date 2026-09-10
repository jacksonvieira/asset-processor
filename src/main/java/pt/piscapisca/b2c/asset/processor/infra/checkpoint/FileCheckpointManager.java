package pt.piscapisca.b2c.asset.processor.infra.checkpoint;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Manages processing state checkpoints for log files (both local and S3-based)
 * to allow resume capabilities in case of interruptions.
 * <p>
 * State files are safely stored in a local {@code .checkpoints} directory using
 * a sanitized version of the target file's key.
 * <p>
 * <b>Atomicity:</b> checkpoints are written via temporary files and an atomic move
 * operation to prevent corrupted or half-written states during sudden crashes or terminations.
 * <p>
 * <b>Windows resilience:</b> on Windows the atomic {@code REPLACE_EXISTING} move can fail
 * transiently with {@link java.nio.file.AccessDeniedException} when antivirus, the search
 * indexer, or a lingering handle briefly locks the destination {@code .state} file. To avoid
 * losing a checkpoint over a momentary lock, {@link #saveCheckpoint(String, long)} retries the
 * move with a short backoff and falls back to a non-atomic replace as a last resort.
 */
@Slf4j
public class FileCheckpointManager {

	private static final Path CHECKPOINT_DIR = Paths.get( ".checkpoints" );

	/**
	 * Number of times a filesystem move/delete is retried when it fails with a transient lock
	 * ({@link java.nio.file.AccessDeniedException} / {@link FileSystemException}) — typical on Windows.
	 */
	private static final int MAX_FS_ATTEMPTS = 5;

	/**
	 * Base backoff between retries (multiplied by the attempt number for a simple linear backoff).
	 */
	private static final long FS_RETRY_BASE_DELAY_MS = 50L;

	static {
		try {
			Files.createDirectories( CHECKPOINT_DIR );
		}
		catch ( IOException e ) {
			log.error( "Could not create checkpoint directory | path={} | {}", CHECKPOINT_DIR,
					B2CExceptionUtils.toMap( e ) );
		}
	}

	/**
	 * Reads the last successfully processed line number for a given file key.
	 *
	 * @return the last processed line number, or {@code 0L} if no checkpoint exists or if an error occurs
	 * @unique identifier of the file being processed (works for both local paths and S3 keys)
	 */
	public static long readLastProcessedLine( String fileKey ) {
		Path stateFile = getStateFilePath( fileKey );
		if ( !Files.exists( stateFile ) ) {
			return 0L;
		}
		try {
			String content = Files.readString( stateFile, StandardCharsets.UTF_8 ).trim();
			return Long.parseLong( content );
		}
		catch ( Exception e ) {
			log.warn( "Failed to read checkpoint state file, restarting from line 0 | fileKey={} | {}",
					fileKey, B2CExceptionUtils.toMap( e ) );
			return 0L;
		}
	}

	/**
	 * Safely saves the current processing checkpoint using an atomic write strategy.
	 * <p>
	 * Writes the line count to a temporary sibling file first, then atomically replaces
	 * the target state file to avoid partial writes. The replace is retried on transient
	 * filesystem locks (Windows) and falls back to a non-atomic replace as a last resort.
	 *
	 * @param fileKey             unique identifier of the file being processed
	 * @param processedLinesCount the current count of processed lines to persist
	 */
	public static void saveCheckpoint( String fileKey, long processedLinesCount ) {
		Path stateFile = getStateFilePath( fileKey );
		Path tempStateFile = stateFile.resolveSibling( stateFile.getFileName().toString() + ".tmp" );

		try {
			Files.writeString( tempStateFile, String.valueOf( processedLinesCount ),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING );
			moveWithRetry( tempStateFile, stateFile );
		}
		catch ( IOException e ) {
			log.error( "Failed to save checkpoint state | fileKey={} | {}", fileKey, B2CExceptionUtils.toMap( e ) );
			// Best-effort cleanup so a stale .tmp does not accumulate after a failed move.
			try {
				Files.deleteIfExists( tempStateFile );
			}
			catch ( IOException cleanupError ) {
				log.debug( "Could not delete leftover checkpoint temp file | tempFile={} | {}", tempStateFile,
						cleanupError.getMessage() );
			}
		}
	}

	/**
	 * Moves {@code source} onto {@code target}, replacing it, resilient to transient locks.
	 * <p>
	 * Strategy:
	 * <ol>
	 *   <li>Try an atomic {@code REPLACE_EXISTING} move (best guarantee against half-written state).</li>
	 *   <li>If the filesystem does not support atomic moves, immediately fall back to a plain replace.</li>
	 *   <li>If it fails with a transient lock ({@link FileSystemException}, e.g. {@code AccessDeniedException} on
	 *       Windows), back off and retry a few times.</li>
	 *   <li>As a last resort, attempt a non-atomic replace, which frequently succeeds where the atomic variant was
	 *       denied on Windows.</li>
	 * </ol>
	 */
	private static void moveWithRetry( Path source, Path target ) throws IOException {
		FileSystemException lastError = null;

		for ( int attempt = 1; attempt <= MAX_FS_ATTEMPTS; attempt++ ) {
			try {
				Files.move( source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
				return;
			}
			catch ( AtomicMoveNotSupportedException e ) {
				// Filesystem cannot do atomic moves — a plain replace is the correct behaviour here.
				Files.move( source, target, StandardCopyOption.REPLACE_EXISTING );
				return;
			}
			catch ( FileSystemException e ) {
				// Includes AccessDeniedException — usually a momentary lock by antivirus / indexer on Windows.
				lastError = e;
				if ( attempt < MAX_FS_ATTEMPTS ) {
					log.debug( "Transient lock moving checkpoint, retrying | attempt={}/{} | target={} | {}",
							attempt, MAX_FS_ATTEMPTS, target, e.getMessage() );
					sleepQuietly( FS_RETRY_BASE_DELAY_MS * attempt );
				}
			}
		}

		// Last resort: non-atomic replace often succeeds where the atomic move kept being denied on Windows.
		try {
			Files.move( source, target, StandardCopyOption.REPLACE_EXISTING );
		}
		catch ( IOException e ) {
			if ( lastError != null ) {
				e.addSuppressed( lastError );
			}
			throw e;
		}
	}

	private static void sleepQuietly( long millis ) {
		try {
			Thread.sleep( millis );
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Clears and deletes the checkpoint state file once the target source file
	 * has been 100% successfully processed. Retries on transient filesystem locks (Windows).
	 *
	 * @param fileKey unique identifier of the completed file
	 */
	public static void clearCheckpoint( String fileKey ) {
		Path stateFile = getStateFilePath( fileKey );
		FileSystemException lastError = null;

		for ( int attempt = 1; attempt <= MAX_FS_ATTEMPTS; attempt++ ) {
			try {
				Files.deleteIfExists( stateFile );
				return;
			}
			catch ( FileSystemException e ) {
				// Transient lock (Windows). A stale checkpoint is harmless — it is overwritten on the next run —
				// so this is only best-effort.
				lastError = e;
				if ( attempt < MAX_FS_ATTEMPTS ) {
					sleepQuietly( FS_RETRY_BASE_DELAY_MS * attempt );
				}
			}
			catch ( IOException e ) {
				log.warn( "Could not delete checkpoint file after completion | fileKey={} | {}",
						fileKey, B2CExceptionUtils.toMap( e ) );
				return;
			}
		}

		log.warn( "Could not delete checkpoint file after completion (still locked after {} attempts) | fileKey={} | {}",
				MAX_FS_ATTEMPTS, fileKey, lastError != null ? lastError.getMessage() : "unknown" );
	}

	/**
	 * Generates a safe, sanitized local file path for a given file key by replacing
	 * invalid filesystem characters (such as S3 slashes or absolute path tokens) with underscores.
	 *
	 * @param fileKey original file path or S3 key
	 * @return a safe resolved {@link Path} inside the checkpoint directory
	 */
	private static Path getStateFilePath( String fileKey ) {
		String safeName = fileKey.replaceAll( "[^a-zA-Z0-9.-]", "_" ) + ".state";
		return CHECKPOINT_DIR.resolve( safeName );
	}
}
