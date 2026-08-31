package pt.piscapisca.b2c.asset.processor.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;

import static org.jooq.impl.DSL.field;

@Slf4j
@RequiredArgsConstructor
public class VehicleRepository {

	private final DSLContext dsl;

	public static final String ENGINE_VEHICLE = "vehicle";

	public boolean existsByIdAndActiveIsTrue( Integer vehicleId ) {
		if ( vehicleId == null ) {
			return false;
		}
		log.trace( "Verifying if active vehicle exists by id={}", vehicleId );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_VEHICLE )
						.where( field( "active" ).eq( true )
								.and( field( "id" ).eq( vehicleId ) ) )
		);
	}

	public boolean existsByUuidAndActiveIsTrue( String vehicleUuid ) {
		if ( vehicleUuid == null || vehicleUuid.isBlank() ) {
			return false;
		}
		log.trace( "Verifying if active vehicle exists by uuid={}", vehicleUuid );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_VEHICLE )
						.where( field( "active" ).eq( true )
								.and( field( "vuuid" ).eq( vehicleUuid ) ) )
		);
	}

	public boolean existsActiveVehicleByIdAndPersonId( Integer vehicleId, Integer personId ) {
		if ( vehicleId == null || personId == null ) {
			return false;
		}
		log.trace( "Verifying if active vehicle id={} belongs to personId={}", vehicleId, personId );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_VEHICLE )
						.where( field( "active" ).eq( true )
								.and( field( "id" ).eq( vehicleId ) )
								.and( field( "person_id" ).eq( personId ) ) )
		);
	}

	public boolean existsActiveVehicleByUuidAndPersonId( String vehicleUuid, Integer personId ) {
		if ( vehicleUuid == null || vehicleUuid.isBlank() || personId == null ) {
			return false;
		}
		log.trace( "Verifying if active vehicle uuid={} belongs to personId={}", vehicleUuid, personId );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_VEHICLE )
						.where( field( "active" ).eq( true )
								.and( field( "vuuid" ).eq( vehicleUuid ) )
								.and( field( "person_id" ).eq( personId ) ) )
		);
	}
}
