package pt.piscapisca.b2c.asset.processor.executor;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes each orphan log line to a per-source {@code *_to_remove.txt} report file.
 * <p>
 * <b>Concurrency:</b> a single {@link BufferedWriter} is kept open per destination file and reused across lines,
 * instead of opening and closing the file on every single line. Writes are serialised per destination file (not
 * globally), so orphans from different source files never contend with each other — a major improvement over the
 * previous {@code synchronized( WriteToRemoveFileOrphanActionExecutor.class )} global lock that serialised every
 * worker thread across every file.
 * <p>
 * <b>Durability:</b> each line is flushed immediately after being written, so a crash cannot leave a checkpoint ahead
 * of the report contents.
 * <p>
 * <b>Lifecycle:</b> {@link #closeWriterFor(Path)} should be called when a source file finishes (frees the file handle
 * promptly), and {@link #close()} closes any remaining writers at the end of the run.
 */
@Slf4j
public class WriteToRemoveFileOrphanActionExecutor implements OrphanActionExecutor, AutoCloseable {

	private static final String STATUS_WRITTEN = "WRITTEN_TO_REMOVE_FILE";

	private static final String STATUS_ERROR_IO = "ERROR_IO: ";

	private static final String OUTPUT_DIR_NAME = "orphan_removal_reports";

	/**
	 * One writer per destination {@code *_to_remove.txt} path. {@link ConcurrentHashMap#computeIfAbsent} guarantees a
	 * single writer is opened per path even under concurrent access.
	 */
	private final Map<Path, BufferedWriter> writers = new ConcurrentHashMap<>();

	@Override
	public OrphanAction supports() {
		return OrphanAction.WRITE_TO_REMOVE_FILE;
	}

	@Override
	public OrphanActionOutcome execute( Path file ) {
		return OrphanActionOutcome.failure( "Use executeForLogLine instead" );
	}

	public OrphanActionOutcome executeForLogLine( String rawLine, Path originalLogFile ) {
		try {
			Path removeFilePath = resolveRemoveFilePath( originalLogFile );
			BufferedWriter writer = writers.computeIfAbsent( removeFilePath, this::openWriter );

			// Serialise only writes to the SAME destination file; different files proceed in parallel.
			synchronized ( writer ) {
				writer.write( rawLine );
				writer.newLine();
				writer.flush();
			}

			log.debug( "Successfully wrote line to remove file | target={}", removeFilePath );
			return OrphanActionOutcome.success( STATUS_WRITTEN + ":" + removeFilePath.getFileName() );
		}
		catch ( UncheckedIOException | IOException e ) {
			log.error( "Failed to process remove action for line | {}", B2CExceptionUtils.toMap( e ) );
			return OrphanActionOutcome.failure( STATUS_ERROR_IO + e.getMessage() );
		}
	}

	private Path resolveRemoveFilePath( Path originalLogFile ) {
		Path parentDir = originalLogFile.getParent();
		String originalFileName = originalLogFile.getFileName().toString();

		Path reportsDir = parentDir != null ? parentDir.resolve( OUTPUT_DIR_NAME ) : Paths.get( OUTPUT_DIR_NAME );
		String removeFileName = originalFileName.replace( ".txt", "_to_remove.txt" );
		return reportsDir.resolve( removeFileName );
	}

	/**
	 * Opens (creating parent directories on demand) a buffered writer in CREATE/APPEND mode. Used as the mapping
	 * function of {@link ConcurrentHashMap#computeIfAbsent}; since that contract forbids checked exceptions, an
	 * {@link IOException} is rethrown as an {@link UncheckedIOException} and unwrapped by the caller.
	 */
	private BufferedWriter openWriter( Path removeFilePath ) {
		try {
			Path reportsDir = removeFilePath.getParent();
			if ( reportsDir != null ) {
				Files.createDirectories( reportsDir );
			}
			return Files.newBufferedWriter( removeFilePath, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND );
		}
		catch ( IOException e ) {
			throw new UncheckedIOException( e );
		}
	}

	/**
	 * Flushes and closes the writer associated with the given source log file (if any). Safe to call multiple times.
	 *
	 * @param originalLogFile the source log file whose {@code *_to_remove.txt} writer should be released
	 */
	public void closeWriterFor( Path originalLogFile ) {
		Path removeFilePath = resolveRemoveFilePath( originalLogFile );
		BufferedWriter writer = writers.remove( removeFilePath );
		closeQuietly( removeFilePath, writer );
	}

	/**
	 * Closes every open writer. Invoked once at the end of a run.
	 */
	@Override
	public void close() {
		writers.forEach( this::closeQuietly );
		writers.clear();
	}

	private void closeQuietly( Path removeFilePath, BufferedWriter writer ) {
		if ( writer == null ) {
			return;
		}
		synchronized ( writer ) {
			try {
				writer.flush();
				writer.close();
			}
			catch ( IOException e ) {
				log.warn( "Failed to close remove-file writer | target={} | {}", removeFilePath,
						B2CExceptionUtils.toMap( e ) );
			}
		}
	}
}
