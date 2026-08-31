package pt.piscapisca.b2c.asset.processor;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.AssetDataDTO;
import pt.piscapisca.b2c.asset.processor.dto.AssetGarbageCollectorType;
import pt.piscapisca.b2c.hashids.SecretId;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class PathDataExtractor {

	private static final String COMPANY_PREFIX = "companies/company_";

	// Regex to identify and extract a formatted UUID (e.g., d17624b6-a16c-11eb-9628-23636e07561c)
	private static final String UUID_REGEX = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";

	// Helper: segmento de path (não atravessa /)
	private static final String SEG = "([^/]+?)";

	// COMPANY: vehicle assets (with vehicle_id or uuid directory)
	private static final Pattern COMPANY_VEHICLE_ASSET_PATTERN = Pattern.compile(
			COMPANY_PREFIX + SEG +
					"(?:/stand_" + SEG + ")?" +
					"(?:/vehicle_" + SEG + "|/" + UUID_REGEX + ")" +
					"(?:/[^/]+)*/([^/]+)$"
			, Pattern.CASE_INSENSITIVE
	);

	// COMPANY: stand assets (no vehicle segment)
	private static final Pattern COMPANY_STAND_ASSET_PATTERN = Pattern.compile(
			COMPANY_PREFIX + SEG + "/stand_" + SEG +
					"(?:/[^/]+)*/([^/]+)$"
			, Pattern.CASE_INSENSITIVE
	);

	// COMPANY: logo only (file directly under company_X, no subdirs)
	private static final Pattern COMPANY_LOGO_PATTERN = Pattern.compile(
			COMPANY_PREFIX + SEG + "/([^/]+)$"
			, Pattern.CASE_INSENSITIVE
	);

	// PERSON: vehicle assets (with vehicle_id or uuid directory)
	private static final Pattern PERSON_VEHICLE_ASSET_PATTERN = Pattern.compile(
			"persons(?:_\\d+)?/person_" + SEG +
					"(?:/vehicle_" + SEG + "|/" + UUID_REGEX + ")" +
					"(?:/[^/]+)*/([^/]+)$"
			, Pattern.CASE_INSENSITIVE
	);

	// PERSON: profile/avatar only (file directly under person_X, no subdirs)
	private static final Pattern PERSON_PROFILE_PATTERN = Pattern.compile(
			"persons(?:_\\d+)?/person_" + SEG + "/([^/]+)$"
			, Pattern.CASE_INSENSITIVE
	);

	private static final Pattern UUID_PATTERN = Pattern.compile( UUID_REGEX, Pattern.CASE_INSENSITIVE );

	/**
	 * Extracts data from the path string. If the path does not match the strict vehicle asset pattern, it returns null,
	 * effectively preventing non-vehicle assets from being passed to the orphaning check.
	 *
	 * @param pathStr The EFS path string.
	 * @return {@link AssetDataDTO} with extracted data, or {@code null} if the path is not a vehicle asset.
	 */
	public AssetDataDTO extractData( String pathStr ) {

		AssetGarbageCollectorType type = AssetGarbageCollectorType.fromPath( pathStr );

		if ( type == AssetGarbageCollectorType.UNKNOWN ) {
			log.warn( "Unknown asset type for path: {}", pathStr );
			return null;
		}

		if ( type == AssetGarbageCollectorType.COMPANY ) {
			return extractCompanyData( pathStr );
		}
		else if ( type == AssetGarbageCollectorType.PERSON ) {
			return extractPersonData( pathStr );
		}
		return null;
	}

	private AssetDataDTO extractCompanyData( String pathStr ) {
		Matcher vehicleMatcher = COMPANY_VEHICLE_ASSET_PATTERN.matcher( pathStr );

		if ( vehicleMatcher.find() ) {
			return buildCompanyVehicleAsset( vehicleMatcher );
		}

		Matcher standMatcher = COMPANY_STAND_ASSET_PATTERN.matcher( pathStr );
		if ( standMatcher.find() ) {
			return buildCompanyStandAsset( standMatcher );
		}

		Matcher logoMatcher = COMPANY_LOGO_PATTERN.matcher( pathStr );
		if ( logoMatcher.find() ) {
			return buildCompanyLogo( logoMatcher );
		}

		log.debug( "Path did not match any COMPANY pattern: {}", pathStr );
		return null;

	}

	private AssetDataDTO extractPersonData( String pathStr ) {
		Matcher vehicleMatcher = PERSON_VEHICLE_ASSET_PATTERN.matcher( pathStr );
		if ( vehicleMatcher.find() ) {
			return buildPersonVehicleAsset( vehicleMatcher );
		}

		Matcher profileMatcher = PERSON_PROFILE_PATTERN.matcher( pathStr );
		if ( profileMatcher.find() ) {
			return buildPersonProfile( profileMatcher );
		}

		log.debug( "Path did not match any PERSON pattern: {}", pathStr );
		return null;
	}

	private AssetDataDTO buildCompanyVehicleAsset( Matcher matcher ) {
		// G1: Company ID
		// G2: Stand ID (Optional)
		// G3: Vehicle Secret ID (If present)
		// G4: Directory UUID (If present)
		// G5: Filename

		String companyIdStr = matcher.group( 1 );
		String standIdStr = matcher.group( 2 );
		String vehicleIdStr = matcher.group( 3 );
		String dirUuid = matcher.group( 4 );
		String filename = matcher.group( 5 );

		AssetDataDTO.AssetDataDTOBuilder builder = AssetDataDTO.builder()
				.assetType( AssetGarbageCollectorType.COMPANY )
				.companyId( new SecretId( companyIdStr ) )
				.fileName( filename );

		if ( standIdStr != null ) {
			builder.standId( new SecretId( standIdStr ) );
		}

		if ( vehicleIdStr != null ) {
			builder.vehicleId( new SecretId( vehicleIdStr ) );
		}

		String finalAssetUuid = null;
		if ( dirUuid != null ) {
			finalAssetUuid = dirUuid;
		}
		else {
			finalAssetUuid = extractUuidFromFilename( filename );
		}

		if ( finalAssetUuid != null ) {
			builder.vehicleUuid( finalAssetUuid );
		}

		return builder.build();
	}

	private AssetDataDTO buildCompanyStandAsset( Matcher matcher ) {
		// G1: Company ID
		// G2: Stand ID
		// G3: Filename

		String companyIdStr = matcher.group( 1 );
		String standIdStr = matcher.group( 2 );
		String filename = matcher.group( 3 );

		return AssetDataDTO.builder()
				.assetType( AssetGarbageCollectorType.COMPANY )
				.companyId( new SecretId( companyIdStr ) )
				.standId( new SecretId( standIdStr ) )
				.fileName( filename )
				.build();
	}

	private AssetDataDTO buildCompanyLogo( Matcher matcher ) {
		// G1: Company ID
		// G2: Filename

		String companyIdStr = matcher.group( 1 );
		String filename = matcher.group( 2 );

		AssetDataDTO.AssetDataDTOBuilder builder = AssetDataDTO.builder()
				.assetType( AssetGarbageCollectorType.COMPANY )
				.companyId( new SecretId( companyIdStr ) )
				.fileName( filename );

		String finalAssetUuid = extractUuidFromFilename( filename );
		if ( finalAssetUuid != null ) {
			builder.vehicleUuid( finalAssetUuid );
		}

		return builder.build();
	}

	private AssetDataDTO buildPersonVehicleAsset( Matcher matcher ) {
		// G1: Person ID
		// G2: Vehicle Secret ID (If present)
		// G3: Directory UUID (If present)
		// G4: Filename

		String personIdStr = matcher.group( 1 );
		String vehicleIdStr = matcher.group( 2 );
		String dirUuid = matcher.group( 3 );
		String filename = matcher.group( 4 );

		AssetDataDTO.AssetDataDTOBuilder builder = AssetDataDTO.builder()
				.assetType( AssetGarbageCollectorType.PERSON )
				.personId( new SecretId( personIdStr ) )
				.fileName( filename );

		if ( vehicleIdStr != null ) {
			builder.vehicleId( new SecretId( vehicleIdStr ) );
		}

		String finalAssetUuid = null;
		if ( dirUuid != null ) {
			finalAssetUuid = dirUuid;
		}
		else {
			finalAssetUuid = extractUuidFromFilename( filename );
		}

		if ( finalAssetUuid != null ) {
			builder.vehicleUuid( finalAssetUuid );
		}

		return builder.build();
	}

	private AssetDataDTO buildPersonProfile( Matcher matcher ) {
		// G1: Person ID
		// G2: Filename

		String personIdStr = matcher.group( 1 );
		String filename = matcher.group( 2 );

		AssetDataDTO.AssetDataDTOBuilder builder = AssetDataDTO.builder()
				.assetType( AssetGarbageCollectorType.PERSON )
				.personId( new SecretId( personIdStr ) )
				.fileName( filename );

		String finalAssetUuid = extractUuidFromFilename( filename );
		if ( finalAssetUuid != null ) {
			builder.vehicleUuid( finalAssetUuid );
		}

		return builder.build();
	}

	private String extractUuidFromFilename( String text ) {
		// We reuse the UUID regex for simplification
		Matcher matcher = UUID_PATTERN.matcher( text );
		return matcher.find() ? matcher.group( 1 ) : null;
	}
}
