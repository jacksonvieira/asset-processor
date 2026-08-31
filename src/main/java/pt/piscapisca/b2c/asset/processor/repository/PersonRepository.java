package pt.piscapisca.b2c.asset.processor.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

@Slf4j
@RequiredArgsConstructor
public class PersonRepository {

	private final DSLContext dsl;

	public static final String ENGINE_PERSON = "person";

	public boolean existsById( Integer personId ) {
		if ( personId == null ) {
			return false;
		}

		log.trace( "Verifying if person exists by id={}", personId );

		return dsl.fetchExists(
				dsl.selectFrom( ENGINE_PERSON )
						.where( DSL.field( "id" ).eq( personId ) )
		);
	}
}
