package pt.piscapisca.b2c.asset.processor.infra.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Repository responsible for querying company entity data and existence checks using jOOQ.
 */
@Slf4j
@RequiredArgsConstructor
public class CompanyRepository {

	private final DSLContext dsl;

	public static final String ENGINE_COMPANY = "company";

	/**
	 * Checks if a company record exists in the database for the given company ID.
	 *
	 * @param companyId the unique identifier of the company
	 * @return {@code true} if the company exists, {@code false} otherwise
	 */
	public boolean existsById( Integer companyId ) {
		if ( companyId == null ) {
			return false;
		}

		log.trace( "Verifying if company exists | companyId={}", companyId );

		return dsl.fetchExists(
				dsl.selectFrom( ENGINE_COMPANY )
						.where( DSL.field( "id" ).eq( companyId ) )
		);
	}
}
