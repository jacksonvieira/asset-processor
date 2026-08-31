package pt.piscapisca.b2c.asset.processor.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

@Slf4j
@RequiredArgsConstructor
public class StandRepository {

	private final DSLContext dsl;

	public static final String ENGINE_STAND = "stand";

	public boolean existsById( Integer standId ) {
		if ( standId == null ) {
			return false;
		}

		log.trace( "Verifying if stand exists by id={}", standId );

		return dsl.fetchExists(
				dsl.selectFrom( ENGINE_STAND )
						.where( DSL.field( "id" ).eq( standId ) )
		);
	}
}
