package pt.piscapisca.b2c.asset.processor.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.repository.CompanyRepository;
import pt.piscapisca.b2c.asset.processor.repository.PersonRepository;
import pt.piscapisca.b2c.asset.processor.repository.StandRepository;
import pt.piscapisca.b2c.asset.processor.repository.VehicleRepository;

import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class AssetLookupCacheService {

	private final VehicleRepository vehicleRepository;

	private final StandRepository standRepository;

	private final PersonRepository personRepository;

	private final CompanyRepository companyRepository;

	private final Cache<Integer, Boolean> vehicleExistenceCache = Caffeine.newBuilder()
			.maximumSize( 10_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<String, Boolean> vehicleExistenceByUuidCache = Caffeine.newBuilder()
			.maximumSize( 10_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<Integer, Boolean> standExistenceCache = Caffeine.newBuilder()
			.maximumSize( 5_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<Integer, Boolean> companyExistenceCache = Caffeine.newBuilder()
			.maximumSize( 5_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<Integer, Boolean> personExistenceCache = Caffeine.newBuilder()
			.maximumSize( 10_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<String, Boolean> vehicleOwnedByPersonCache = Caffeine.newBuilder()
			.maximumSize( 10_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<String, Boolean> vehicleOwnedByPersonUuidCache = Caffeine.newBuilder()
			.maximumSize( 10_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	public boolean vehicleExists( Integer vehicleId ) {
		return vehicleExistenceCache.get( vehicleId, vehicleRepository::existsByIdAndActiveIsTrue );
	}

	public boolean vehicleExists( String vehicleUuid ) {
		return vehicleExistenceByUuidCache.get( vehicleUuid, vehicleRepository::existsByUuidAndActiveIsTrue );
	}

	public boolean standExists( Integer standId ) {
		return standExistenceCache.get( standId, standRepository::existsById );
	}

	public boolean companyExists( Integer companyId ) {
		return companyExistenceCache.get( companyId, companyRepository::existsById );
	}

	public boolean personExists( Integer personId ) {
		return personExistenceCache.get( personId, personRepository::existsById );
	}

	public boolean vehicleBelongsToPerson( Integer vehicleId, Integer personId ) {
		String key = vehicleId + "_" + personId;
		return vehicleOwnedByPersonCache.get( key,
				k -> vehicleRepository.existsActiveVehicleByIdAndPersonId( vehicleId, personId ) );
	}

	public boolean vehicleBelongsToPerson( String vehicleUuid, Integer personId ) {
		String key = vehicleUuid + "_" + personId;
		return vehicleOwnedByPersonUuidCache.get( key,
				k -> vehicleRepository.existsActiveVehicleByUuidAndPersonId( vehicleUuid, personId ) );
	}

	/**
	 * Limpa todos os caches de lookup.
	 */
	public void clearLookupCaches() {
		vehicleExistenceCache.invalidateAll();
		vehicleExistenceByUuidCache.invalidateAll();
		standExistenceCache.invalidateAll();
		companyExistenceCache.invalidateAll();
		personExistenceCache.invalidateAll();
		vehicleOwnedByPersonCache.invalidateAll();
		vehicleOwnedByPersonUuidCache.invalidateAll();
		log.info( "EFS GC lookup caches cleared." );
	}
}
