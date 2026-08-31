package pt.piscapisca.b2c.asset.processor.infra.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Repository responsible for querying stand entity data and existence checks using jOOQ.
 */
@Slf4j
@RequiredArgsConstructor
public class StandRepository {

	private final DSLContext dsl;

	public static final String ENGINE_STAND = "stand";

	/**
	 * Checks if a stand record exists in the database for the given stand ID.
	 *
	 * @param standId the unique identifier of the stand
	 * @return {@code true} if the stand exists, {@code false} otherwise
	 */
	public boolean existsById( Integer standId ) {
		if ( standId == null ) {
			return false;
		}

		log.trace( "Verifying if stand exists | standId={}", standId );

		return dsl.fetchExists(
				dsl.selectFrom( ENGINE_STAND )
						.where( DSL.field( "id" ).eq( standId ) )
		);
	}
}
