package pt.piscapisca.b2c.asset.processor.offset;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@Slf4j
public class FileCheckpointManager {

	private static final Path CHECKPOINT_DIR = Paths.get( ".checkpoints" );

	static {
		try {
			Files.createDirectories( CHECKPOINT_DIR );
		}
		catch ( IOException e ) {
			log.error( "Could not create checkpoint directory", e );
		}
	}

	/**
	 * Lê a última linha processada usando a key do arquivo (funciona para S3 e Local).
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
			log.warn( "Failed to read checkpoint state file, restarting from line 0 | fileKey={}", fileKey, e );
			return 0L;
		}
	}

	/**
	 * Salva o checkpoint de forma atômica utilizando a key do arquivo.
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
			log.error( "Failed to save checkpoint state | fileKey={}", fileKey, e );
		}
	}

	/**
	 * Limpa o state file quando o arquivo for 100% concluído com sucesso.
	 */
	public static void clearCheckpoint( String fileKey ) {
		try {
			Path stateFile = getStateFilePath( fileKey );
			Files.deleteIfExists( stateFile );
		}
		catch ( IOException e ) {
			log.warn( "Could not delete checkpoint file after completion | fileKey={}", fileKey, e );
		}
	}

	private static Path getStateFilePath( String fileKey ) {
		// Substitui caracteres inválidos (como '/' do S3 ou caminhos absolutos) por '_' para virar um nome seguro
		String safeName = fileKey.replaceAll( "[^a-zA-Z0-9.-]", "_" ) + ".state";
		return CHECKPOINT_DIR.resolve( safeName );
	}
}
