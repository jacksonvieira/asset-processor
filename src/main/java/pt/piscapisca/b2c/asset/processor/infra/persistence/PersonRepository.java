package pt.piscapisca.b2c.asset.processor.infra.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Repository responsible for querying person entity data and existence checks using jOOQ.
 */
@Slf4j
@RequiredArgsConstructor
public class PersonRepository {

	private final DSLContext dsl;

	public static final String ENGINE_PERSON = "person";

	/**
	 * Checks if a person record exists in the database for the given person ID.
	 *
	 * @param personId the unique identifier of the person
	 * @return {@code true} if the person exists, {@code false} otherwise
	 */
	public boolean existsById( Integer personId ) {
		if ( personId == null ) {
			return false;
		}

		log.trace( "Verifying if person exists | personId={}", personId );

		return dsl.fetchExists(
				dsl.selectFrom( ENGINE_PERSON )
						.where( DSL.field( "id" ).eq( personId ) )
		);
	}
}
