package pt.piscapisca.b2c.asset.processor.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.infra.persistence.AssetRepository;
import pt.piscapisca.b2c.asset.processor.infra.persistence.CompanyRepository;
import pt.piscapisca.b2c.asset.processor.infra.persistence.PersonRepository;
import pt.piscapisca.b2c.asset.processor.infra.persistence.StandRepository;
import pt.piscapisca.b2c.asset.processor.infra.persistence.VehicleRepository;

import java.time.Duration;

/**
 * Service responsible for caching and verifying entity existence and ownership relationships
 * across repositories using high-performance Caffeine caches.
 */
@Slf4j
@RequiredArgsConstructor
public class AssetLookupCacheService {

	private final VehicleRepository vehicleRepository;

	private final StandRepository standRepository;

	private final PersonRepository personRepository;

	private final CompanyRepository companyRepository;

	private final AssetRepository assetRepository;

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

	private final Cache<Integer, AssetRepository.AssetNames> vehicleAssetNamesByIdCache = Caffeine.newBuilder()
			.maximumSize( 10_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<String, AssetRepository.AssetNames> vehicleAssetNamesByUuidCache = Caffeine.newBuilder()
			.maximumSize( 10_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<Integer, AssetRepository.AssetNames> standAssetNamesCache = Caffeine.newBuilder()
			.maximumSize( 5_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<Integer, AssetRepository.AssetNames> companyAssetNamesCache = Caffeine.newBuilder()
			.maximumSize( 5_000 )
			.expireAfterWrite( Duration.ofMinutes( 15 ) )
			.build();

	private final Cache<Integer, AssetRepository.AssetNames> personAssetNamesCache = Caffeine.newBuilder()
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

	/**
	 * Checks if an active vehicle exists by its unique identifier, utilizing the local cache.
	 *
	 * @param vehicleId the unique identifier of the vehicle
	 * @return {@code true} if the active vehicle exists, {@code false} otherwise
	 */
	public boolean vehicleExists( Integer vehicleId ) {
		return vehicleExistenceCache.get( vehicleId, vehicleRepository::existsByIdAndActiveIsTrue );
	}

	/**
	 * Checks if an active vehicle exists by its UUID string, utilizing the local cache.
	 *
	 * @param vehicleUuid the unique UUID string of the vehicle
	 * @return {@code true} if the active vehicle exists, {@code false} otherwise
	 */
	public boolean vehicleExists( String vehicleUuid ) {
		return vehicleExistenceByUuidCache.get( vehicleUuid, vehicleRepository::existsByUuidAndActiveIsTrue );
	}

	/**
	 * Checks if a stand exists by its unique identifier, utilizing the local cache.
	 *
	 * @param standId the unique identifier of the stand
	 * @return {@code true} if the stand exists, {@code false} otherwise
	 */
	public boolean standExists( Integer standId ) {
		return standExistenceCache.get( standId, standRepository::existsById );
	}

	/**
	 * Checks if a company exists by its unique identifier, utilizing the local cache.
	 *
	 * @param companyId the unique identifier of the company
	 * @return {@code true} if the company exists, {@code false} otherwise
	 */
	public boolean companyExists( Integer companyId ) {
		return companyExistenceCache.get( companyId, companyRepository::existsById );
	}

	/**
	 * Checks if a person exists by their unique identifier, utilizing the local cache.
	 *
	 * @param personId the unique identifier of the person
	 * @return {@code true} if the person exists, {@code false} otherwise
	 */
	public boolean personExists( Integer personId ) {
		return personExistenceCache.get( personId, personRepository::existsById );
	}

	/**
	 * Checks if an active vehicle identified by ID belongs to a specific person, utilizing the local cache.
	 *
	 * @param vehicleId the unique identifier of the vehicle
	 * @param personId  the unique identifier of the person owner
	 * @return {@code true} if the vehicle belongs to the person, {@code false} otherwise
	 */
	public boolean vehicleBelongsToPerson( Integer vehicleId, Integer personId ) {
		String key = vehicleId + "_" + personId;
		return vehicleOwnedByPersonCache.get( key,
				k -> vehicleRepository.existsActiveVehicleByIdAndPersonId( vehicleId, personId ) );
	}

	/**
	 * Checks if an active vehicle identified by UUID belongs to a specific person, utilizing the local cache.
	 *
	 * @param vehicleUuid the unique UUID string of the vehicle
	 * @param personId    the unique identifier of the person owner
	 * @return {@code true} if the vehicle belongs to the person, {@code false} otherwise
	 */
	public boolean vehicleBelongsToPerson( String vehicleUuid, Integer personId ) {
		String key = vehicleUuid + "_" + personId;
		return vehicleOwnedByPersonUuidCache.get( key,
				k -> vehicleRepository.existsActiveVehicleByUuidAndPersonId( vehicleUuid, personId ) );
	}

	/**
	 * Checks (cache-backed) whether the given file is linked to an active vehicle asset by vehicle ID.
	 * The full set of linked names is loaded once per vehicle and reused for subsequent lines,
	 * replacing the previous one-EXISTS-query-per-line pattern.
	 */
	public boolean vehicleAssetLinked( Integer vehicleId, String fileName, boolean isThumbnail ) {
		if ( vehicleId == null || fileName == null || fileName.isBlank() ) {
			return false;
		}
		return vehicleAssetNamesByIdCache
				.get( vehicleId, assetRepository::loadActiveVehicleAssetNamesByVehicleId )
				.contains( fileName, isThumbnail );
	}

	/**
	 * Checks (cache-backed) whether the given file is linked to an active vehicle asset by vehicle UUID.
	 */
	public boolean vehicleAssetLinked( String vehicleUuid, String fileName, boolean isThumbnail ) {
		if ( vehicleUuid == null || vehicleUuid.isBlank() || fileName == null || fileName.isBlank() ) {
			return false;
		}
		return vehicleAssetNamesByUuidCache
				.get( vehicleUuid, assetRepository::loadActiveVehicleAssetNamesByVehicleUuid )
				.contains( fileName, isThumbnail );
	}

	/**
	 * Checks (cache-backed) whether the given file is linked to a stand asset by stand ID.
	 */
	public boolean standAssetLinked( Integer standId, String fileName, boolean isThumbnail ) {
		if ( standId == null || fileName == null || fileName.isBlank() ) {
			return false;
		}
		return standAssetNamesCache
				.get( standId, assetRepository::loadStandAssetNamesByStandId )
				.contains( fileName, isThumbnail );
	}

	/**
	 * Checks (cache-backed) whether the given file is linked to a company logo/logo_type asset by company ID.
	 */
	public boolean companyAssetLinked( Integer companyId, String fileName, boolean isThumbnail ) {
		if ( companyId == null || fileName == null || fileName.isBlank() ) {
			return false;
		}
		return companyAssetNamesCache
				.get( companyId, assetRepository::loadCompanyAssetNames )
				.contains( fileName, isThumbnail );
	}

	/**
	 * Checks (cache-backed) whether the given file is linked to a person profile asset by person ID.
	 */
	public boolean personProfileAssetLinked( Integer personId, String fileName, boolean isThumbnail ) {
		if ( personId == null || fileName == null || fileName.isBlank() ) {
			return false;
		}
		return personAssetNamesCache
				.get( personId, assetRepository::loadPersonProfileAssetNames )
				.contains( fileName, isThumbnail );
	}

	/**
	 * Clears all lookup and existence caches.
	 */
	public void clearLookupCaches() {
		vehicleExistenceCache.invalidateAll();
		vehicleExistenceByUuidCache.invalidateAll();
		standExistenceCache.invalidateAll();
		companyExistenceCache.invalidateAll();
		personExistenceCache.invalidateAll();
		vehicleOwnedByPersonCache.invalidateAll();
		vehicleOwnedByPersonUuidCache.invalidateAll();
		vehicleAssetNamesByIdCache.invalidateAll();
		vehicleAssetNamesByUuidCache.invalidateAll();
		standAssetNamesCache.invalidateAll();
		companyAssetNamesCache.invalidateAll();
		personAssetNamesCache.invalidateAll();
		log.info( "EFS GC lookup caches cleared." );
	}
}
