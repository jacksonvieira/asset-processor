package pt.piscapisca.b2c.asset.processor.infra.source;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.domain.model.S3LogFile;
import pt.piscapisca.b2c.asset.processor.domain.source.LogFileSource;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.Scope;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local file system implementation of {@link LogFileSource} used for reading
 * and managing garbage collection log files during local development or debugging.
 */
@Slf4j
public class LocalLogFileSource implements LogFileSource {

	private static final Pattern COMPANY_PATTERN = Pattern.compile( "^company_([^_]+)_files\\.txt$" );

	private static final Pattern PERSON_PATTERN = Pattern.compile( "^person_([^_]+)_files\\.txt$" );

	private static final String PROCESSED_SUFFIX = "_processed.txt";

	private final Path localDirectory;

	/**
	 * Constructs a new {@code LocalLogFileSource} with the specified local directory path.
	 *
	 * @param localPathStr the path to the local directory containing log files
	 */
	public LocalLogFileSource( String localPathStr ) {
		this.localDirectory = Paths.get( localPathStr );
	}

	@Override
	public List<S3LogFile> listPendingFiles( Scope scope, Set<String> idFilter, boolean skipAlreadyProcessed ) {
		Pattern pattern = scope == Scope.COMPANIES ? COMPANY_PATTERN : PERSON_PATTERN;

		if ( !Files.exists( localDirectory ) || !Files.isDirectory( localDirectory ) ) {
			log.warn( "Local log directory does not exist or is not a directory | directory={}", localDirectory );
			return List.of();
		}

		try ( Stream<Path> pathStream = Files.list( localDirectory ) ) {
			List<S3LogFile> allFiles = pathStream
					.filter( Files::isRegularFile )
					.filter( p -> {
						String fileName = p.getFileName().toString();
						// Ignore files that already have the processed suffix
						if ( fileName.endsWith( PROCESSED_SUFFIX ) ) {
							return false;
						}
						Matcher m = pattern.matcher( fileName );
						return m.find();
					} )
					.map( p -> {
						String fileName = p.getFileName().toString();
						Matcher m = pattern.matcher( fileName );
						if ( m.find() ) {
							String entityId = m.group( 1 );
							try {
								long size = Files.size( p );
								// Use the absolute path string in the 'key' field to maintain model compatibility
								return new S3LogFile( p.toAbsolutePath().toString(), entityId, size );
							}
							catch ( IOException e ) {
								log.error( "Failed to read size for local file | file={} | error={}", p, e.getMessage(),
										e );
								return null;
							}
						}
						return null;
					} )
					.filter( java.util.Objects::nonNull )
					.toList();

			// Apply ID filter if present
			List<S3LogFile> filtered = ( idFilter == null || idFilter.isEmpty() )
					? allFiles
					: allFiles.stream().filter( f -> idFilter.contains( f.entityId() ) ).toList();

			if ( skipAlreadyProcessed ) {
				// In local mode, if a corresponding file with the processed suffix exists, filter it out
				return filtered.stream().filter( f -> !isProcessed( f ) ).toList();
			}

			return filtered;
		}
		catch ( IOException e ) {
			log.error( "Error listing local files in directory | directory={} | error={}", localDirectory,
					B2CExceptionUtils.toMap( e ), e );
			return List.of();
		}
	}

	@Override
	public BufferedReader openReader( S3LogFile file ) {
		try {
			Path path = Paths.get( file.key() );
			return Files.newBufferedReader( path, StandardCharsets.UTF_8 );
		}
		catch ( IOException e ) {
			throw new RuntimeException( "Failed to open reader for local file: " + file.key(), e );
		}
	}

	@Override
	public void markAsProcessed( S3LogFile file, String runId, String orphanAction ) {
		try {
			Path originalPath = Paths.get( file.key() );
			Path parentDir = originalPath.getParent();
			String originalFileName = originalPath.getFileName().toString();

			// Create the new file name with the suffix indicating it has been processed
			String newFileName = originalFileName.replace( ".txt", PROCESSED_SUFFIX );
			Path processedPath = parentDir != null ? parentDir.resolve( newFileName ) : Paths.get( newFileName );

			// Move/Rename the original file to the new processed file
			Files.move( originalPath, processedPath, StandardCopyOption.REPLACE_EXISTING );
			log.info( "Marked local file as processed by renaming | processedPath={} | runId={}", processedPath,
					runId );
		}
		catch ( IOException e ) {
			log.error( "Failed to mark local file as processed | file={} | error={}", file.key(), e.getMessage(), e );
		}
	}

	@Override
	public boolean isProcessed( S3LogFile file ) {
		try {
			Path originalPath = Paths.get( file.key() );
			Path parentDir = originalPath.getParent();
			String originalFileName = originalPath.getFileName().toString();
			String newFileName = originalFileName.replace( ".txt", PROCESSED_SUFFIX );
			Path processedPath = parentDir != null ? parentDir.resolve( newFileName ) : Paths.get( newFileName );

			return Files.exists( processedPath );
		}
		catch ( Exception e ) {
			return false;
		}
	}
}
