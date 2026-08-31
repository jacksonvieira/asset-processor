package pt.piscapisca.b2c.asset.processor.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

@Slf4j
@RequiredArgsConstructor
public class CompanyRepository {

	private final DSLContext dsl;

	public static final String ENGINE_COMPANY = "company";

	public boolean existsById( Integer companyId ) {
		if ( companyId == null ) {
			return false;
		}

		log.trace( "Verifying if company exists by id={}", companyId );

		return dsl.fetchExists(
				dsl.selectFrom( ENGINE_COMPANY )
						.where( DSL.field( "id" ).eq( companyId ) )
		);
	}
}
