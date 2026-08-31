package pt.piscapisca.b2c.asset.processor;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import pt.piscapisca.b2c.asset.processor.config.AppConfig;
import pt.piscapisca.b2c.asset.processor.config.ConfigLoader;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand;
import pt.piscapisca.b2c.asset.processor.infra.factory.DatabaseFactory;
import pt.piscapisca.b2c.asset.processor.infra.factory.ServiceFactory;
import pt.piscapisca.b2c.asset.processor.service.EfsGarbageCollectorService;

@Slf4j
@Command( name = "asset-processor", mixinStandardHelpOptions = true, version = "1.0",
		description = "Executes EFS Asset Garbage Collection" )
public class Main implements Runnable {

	@CommandLine.ArgGroup( exclusive = false, multiplicity = "1" )
	private DevAssetGarbageCollectionCommand command;

	@Override
	public void run() {
		Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
		dotenv.entries().forEach( entry -> System.setProperty( entry.getKey(), entry.getValue() ) );

		command.validate();
		log.info( "Iniciando processamento com scope={}", command.scope() );

		try {
			AppConfig config = ConfigLoader.load();
			DSLContext dsl = DatabaseFactory.createDSLContext( config.database() );

			try {
				// O try-with-resources garante o fechamento do serviço e de todos os recursos internos (S3Client, etc.)
				try ( EfsGarbageCollectorService efsProcessorApplication = ServiceFactory.createGarbageCollectorService(
						dsl,
						config
				) ) {
					efsProcessorApplication.run( command );
					log.info( "Processamento finalizado com sucesso!" );
				}
			}
			finally {
				DatabaseFactory.close();
			}
		}
		catch ( Exception e ) {
			log.error( "Erro fatal durante o processamento", e );
			System.exit( 1 );
		}
	}

	public static void main( String[] args ) {
		int exitCode = new CommandLine( new Main() ).execute( args );
		System.exit( exitCode );
	}
}
