package pt.piscapisca.b2c.asset.processor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {

	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile( "\\$\\{(.*?)\\}" );

	public static AppConfig load() throws Exception {
		ObjectMapper mapper = new ObjectMapper( new YAMLFactory() );
		mapper.setPropertyNamingStrategy( PropertyNamingStrategies.KEBAB_CASE );

		String yamlContent = readApplicationYml();
		Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

		// Encontra e substitui dinamicamente qualquer ${VAR_NAME} do YAML
		Matcher matcher = PLACEHOLDER_PATTERN.matcher( yamlContent );
		StringBuilder sb = new StringBuilder();

		while ( matcher.find() ) {
			String key = matcher.group( 1 );
			String resolvedValue = resolveConfigurationValue( key, dotenv );

			if ( resolvedValue != null ) {
				// Matcher.quoteReplacement protege contra caracteres especiais no valor (como cifrões)
				matcher.appendReplacement( sb, Matcher.quoteReplacement( resolvedValue ) );
			}
			else {
				// Mantém o placeholder original caso não encontre valor em nenhuma fonte
				matcher.appendReplacement( sb, Matcher.quoteReplacement( matcher.group( 0 ) ) );
			}
		}
		matcher.appendTail( sb );

		return mapper.readValue( sb.toString(), AppConfig.class );
	}

	private static String readApplicationYml() {
		try ( InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream( "application.yml" ) ) {
			if ( is == null ) {
				throw new RuntimeException( "application.yml não encontrado no classpath!" );
			}
			try ( Scanner scanner = new Scanner( is, StandardCharsets.UTF_8 ) ) {
				return scanner.useDelimiter( "\\A" ).next();
			}
		}
		catch ( Exception e ) {
			throw new RuntimeException( "Falha ao ler o arquivo application.yml", e );
		}
	}

	private static String resolveConfigurationValue( String key, Dotenv dotenv ) {
		// 1. Prioridade máxima: Parâmetro via linha de comando (-Dkey=value)
		String value = System.getProperty( key );

		// 2. Segunda prioridade: Arquivo .env local
		if ( value == null && dotenv != null ) {
			value = dotenv.get( key );
		}

		// 3. Terceira prioridade: Variável de ambiente do Sistema Operacional
		if ( value == null ) {
			value = System.getenv( key );
		}

		return value;
	}
}
