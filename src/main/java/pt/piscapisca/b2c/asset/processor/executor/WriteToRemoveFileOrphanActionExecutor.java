package pt.piscapisca.b2c.asset.processor.executor;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Slf4j
public class WriteToRemoveFileOrphanActionExecutor implements OrphanActionExecutor {

	private static final String STATUS_WRITTEN = "WRITTEN_TO_REMOVE_FILE";

	private static final String STATUS_ERROR_IO = "ERROR_IO: ";

	@Override
	public OrphanAction supports() {
		return OrphanAction.WRITE_TO_REMOVE_FILE;
	}

	@Override
	public OrphanActionOutcome execute( Path file ) {
		return OrphanActionOutcome.failure( "Use executeForLogLine instead" );
	}

	public OrphanActionOutcome executeForLogLine( String rawLine, Path originalLogFile ) {
		// Sincronizamos para evitar concorrência de múltiplas threads escrevendo no mesmo arquivo _to_remove.txt
		synchronized ( WriteToRemoveFileOrphanActionExecutor.class ) {
			try {
				Path parentDir = originalLogFile.getParent();
				String originalFileName = originalLogFile.getFileName().toString();

				// 1. Caminho do arquivo de remoção
				String removeFileName = originalFileName.replace( ".txt", "_to_remove.txt" );
				Path removeFilePath =
						parentDir != null ? parentDir.resolve( removeFileName ) : Paths.get( removeFileName );

				// 2. Escreve a linha no arquivo _to_remove.txt de forma segura (apenas append)
				try ( BufferedWriter writer = Files.newBufferedWriter( removeFilePath, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND ) ) {
					writer.write( rawLine );
					writer.newLine();
				}

				log.debug( "Wrote line to remove file | line={} | target={}", rawLine, removeFilePath );
				return OrphanActionOutcome.success( STATUS_WRITTEN + ":" + removeFilePath.getFileName() );
			}
			catch ( IOException e ) {
				log.error( "Failed to process remove action for line | line={} | {}", rawLine, e.getMessage() );
				return OrphanActionOutcome.failure( STATUS_ERROR_IO + e.getMessage() );
			}
		}
	}
}
