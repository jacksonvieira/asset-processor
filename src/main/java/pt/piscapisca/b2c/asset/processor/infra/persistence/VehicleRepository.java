package pt.piscapisca.b2c.asset.processor.infra.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;

import static org.jooq.impl.DSL.field;

/**
 * Repository responsible for querying active vehicle entity records, ownership validations,
 * and existence checks using jOOQ.
 */
@Slf4j
@RequiredArgsConstructor
public class VehicleRepository {

	private final DSLContext dsl;

	public static final String ENGINE_VEHICLE = "vehicle";

	/**
	 * Checks if an active vehicle exists in the database for a given vehicle ID.
	 *
	 * @param vehicleId the unique identifier of the vehicle
	 * @return {@code true} if an active vehicle exists, {@code false} otherwise
	 */
	public boolean existsByIdAndActiveIsTrue( Integer vehicleId ) {
		if ( vehicleId == null ) {
			return false;
		}
		log.trace( "Verifying if active vehicle exists | vehicleId={}", vehicleId );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_VEHICLE )
						.where( field( "active" ).eq( true )
								.and( field( "id" ).eq( vehicleId ) ) )
		);
	}

	/**
	 * Checks if an active vehicle exists in the database for a given vehicle UUID.
	 *
	 * @param vehicleUuid the unique UUID string of the vehicle
	 * @return {@code true} if an active vehicle exists, {@code false} otherwise
	 */
	public boolean existsByUuidAndActiveIsTrue( String vehicleUuid ) {
		if ( vehicleUuid == null || vehicleUuid.isBlank() ) {
			return false;
		}
		log.trace( "Verifying if active vehicle exists | vehicleUuid={}", vehicleUuid );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_VEHICLE )
						.where( field( "active" ).eq( true )
								.and( field( "vuuid" ).eq( vehicleUuid ) ) )
		);
	}

	/**
	 * Checks if an active vehicle exists for a given vehicle ID and belongs to a specific person.
	 *
	 * @param vehicleId the unique identifier of the vehicle
	 * @param personId  the unique identifier of the person owner
	 * @return {@code true} if the active vehicle belongs to the person, {@code false} otherwise
	 */
	public boolean existsActiveVehicleByIdAndPersonId( Integer vehicleId, Integer personId ) {
		if ( vehicleId == null || personId == null ) {
			return false;
		}
		log.trace( "Verifying if active vehicle belongs to person | vehicleId={} | personId={}", vehicleId, personId );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_VEHICLE )
						.where( field( "active" ).eq( true )
								.and( field( "id" ).eq( vehicleId ) )
								.and( field( "person_id" ).eq( personId ) ) )
		);
	}

	/**
	 * Checks if an active vehicle exists for a given vehicle UUID and belongs to a specific person.
	 *
	 * @param vehicleUuid the unique UUID string of the vehicle
	 * @param personId    the unique identifier of the person owner
	 * @return {@code true} if the active vehicle belongs to the person, {@code false} otherwise
	 */
	public boolean existsActiveVehicleByUuidAndPersonId( String vehicleUuid, Integer personId ) {
		if ( vehicleUuid == null || vehicleUuid.isBlank() || personId == null ) {
			return false;
		}
		log.trace( "Verifying if active vehicle belongs to person | vehicleUuid={} | personId={}", vehicleUuid,
				personId );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_VEHICLE )
						.where( field( "active" ).eq( true )
								.and( field( "vuuid" ).eq( vehicleUuid ) )
								.and( field( "person_id" ).eq( personId ) ) )
		);
	}
}
