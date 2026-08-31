package pt.piscapisca.b2c.asset.processor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ConfigLoader {

	public static AppConfig load() throws Exception {
		ObjectMapper mapper = new ObjectMapper( new YAMLFactory() );
		mapper.setPropertyNamingStrategy( PropertyNamingStrategies.KEBAB_CASE );

		// 1. Lê o application.yml como uma String crua
		String yamlContent;
		try ( InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream( "application.yml" ) ) {
			if ( is == null ) {
				throw new RuntimeException( "application.yml não encontrado no classpath!" );
			}
			try ( Scanner scanner = new Scanner( is, StandardCharsets.UTF_8 ) ) {
				yamlContent = scanner.useDelimiter( "\\A" ).next();
			}
		}

		// 2. Carrega o Dotenv para garantir que temos os valores
		Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

		// 3. Substitui dinamicamente os padrões ${VAR_NAME} pelos valores do .env ou variáveis de sistema
		for ( var entry : dotenv.entries() ) {
			String placeholder = "${" + entry.getKey() + "}";
			if ( yamlContent.contains( placeholder ) ) {
				yamlContent = yamlContent.replace( placeholder, entry.getValue() );
			}
		}

		// (Opcional) Também cobre variáveis de ambiente normais do OS se houverem
		for ( String key : System.getenv().keySet() ) {
			String placeholder = "${" + key + "}";
			if ( yamlContent.contains( placeholder ) ) {
				yamlContent = yamlContent.replace( placeholder, System.getenv( key ) );
			}
		}

		// 4. Passa o conteúdo tratado (já com os valores reais) para o Jackson desserializar
		return mapper.readValue( yamlContent, AppConfig.class );
	}
}
