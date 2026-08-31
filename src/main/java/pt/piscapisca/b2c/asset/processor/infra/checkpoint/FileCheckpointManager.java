package pt.piscapisca.b2c.asset.processor.infra.checkpoint;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Manages processing state checkpoints for log files (both local and S3-based)
 * to allow resume capabilities in case of interruptions.
 * <p>
 * State files are safely stored in a local {@code .checkpoints} directory using
 * a sanitized version of the target file's key.
 * <p>
 * <b>Atomicity:</b> checkpoints are written via temporary files and an atomic move
 * operation to prevent corrupted or half-written states during sudden crashes or terminations.
 */
@Slf4j
public class FileCheckpointManager {

	private static final Path CHECKPOINT_DIR = Paths.get( ".checkpoints" );

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
	 * the target state file to avoid partial writes.
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
			Files.move( tempStateFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
		}
		catch ( IOException e ) {
			log.error( "Failed to save checkpoint state | fileKey={} | {}", fileKey, B2CExceptionUtils.toMap( e ) );
		}
	}

	/**
	 * Clears and deletes the checkpoint state file once the target source file
	 * has been 100% successfully processed.
	 *
	 * @param fileKey unique identifier of the completed file
	 */
	public static void clearCheckpoint( String fileKey ) {
		try {
			Path stateFile = getStateFilePath( fileKey );
			Files.deleteIfExists( stateFile );
		}
		catch ( IOException e ) {
			log.warn( "Could not delete checkpoint file after completion | fileKey={} | {}",
					fileKey, B2CExceptionUtils.toMap( e ) );
		}
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
