package pt.piscapisca.b2c.asset.processor.executor;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;
import pt.piscapisca.b2c.utils.B2CExceptionUtils;

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

	private static final String OUTPUT_DIR_NAME = "orphan_removal_reports";

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

				// 1. Cria um diretório dedicado e organizado no mesmo nível para os relatórios
				Path reportsDir = parentDir != null ? parentDir.resolve( OUTPUT_DIR_NAME ) : Paths.get( OUTPUT_DIR_NAME );
				Files.createDirectories( reportsDir );

				// 2. Caminho do arquivo de remoção dentro do novo diretório
				String removeFileName = originalFileName.replace( ".txt", "_to_remove.txt" );
				Path removeFilePath = reportsDir.resolve( removeFileName );

				// 3. Escreve a linha no arquivo _to_remove.txt de forma segura (apenas append)
				try ( BufferedWriter writer = Files.newBufferedWriter( removeFilePath, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND ) ) {
					writer.write( rawLine );
					writer.newLine();
				}

				log.debug( "Successfully wrote line to remove file | target={}", removeFilePath );
				return OrphanActionOutcome.success( STATUS_WRITTEN + ":" + removeFilePath.getFileName() );
			}
			catch ( IOException e ) {
				log.error( "Failed to process remove action for line | {}", B2CExceptionUtils.toMap( e ) );
				return OrphanActionOutcome.failure( STATUS_ERROR_IO + e.getMessage() );
			}
		}
	}
}
