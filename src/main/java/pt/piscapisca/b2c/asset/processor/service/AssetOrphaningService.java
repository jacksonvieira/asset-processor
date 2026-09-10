package pt.piscapisca.b2c.asset.processor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.AssetDataDTO;

/**
 * Service responsible for identifying orphan assets stored in EFS.
 * <p>
 * An asset is considered "orphan" if it is no longer referenced by an active Vehicle or Person entity in the database.
 */
@Slf4j
@RequiredArgsConstructor
public class AssetOrphaningService {

	private final AssetLookupCacheService assetLookupCacheService;

	/**
	 * Determines if a potential asset file found in the EFS should be considered an orphan.
	 *
	 * @param candidateAsset asset data loaded from EFS.
	 * @return {@code true} if the asset is an orphan and should be deleted; {@code false} otherwise.
	 */
	public boolean isOrphan( AssetDataDTO candidateAsset ) {
		if ( candidateAsset == null ) {
			log.debug( "Asset DTO is null, skipping evaluation." );
			return false;
		}

		if ( candidateAsset.getAssetType() == null ) {
			log.warn( "Asset type is null | candidateAsset={}", candidateAsset );
			return false;
		}

		log.debug(
				"Evaluating asset | assetType={} | fileName={}", candidateAsset.getAssetType(),
				candidateAsset.getFileName() );

		return switch ( candidateAsset.getAssetType() ) {
			case COMPANY -> isCompanyAssetOrphan( candidateAsset );
			case PERSON -> isPersonAssetOrphan( candidateAsset );
			default -> {
				log.warn( "Unknown asset type encountered | assetType={}", candidateAsset.getAssetType() );
				yield false;
			}
		};
	}

	/**
	 * Checks if a COMPANY-related asset is an orphan. The evaluation logic prioritizes checking if the parent Vehicle
	 * is active before checking the asset link.
	 *
	 * @param candidateAsset The asset data.
	 * @return {@code true} if the asset is not referenced by an active vehicle.
	 */
	private boolean isCompanyAssetOrphan( AssetDataDTO candidateAsset ) {

		ResolvedAssetData resolvedData = resolveAndValidateFileName( candidateAsset.getFileName() );
		if ( resolvedData == null ) {
			return false;
		}

		String searchFileName = resolvedData.lookupFileName();
		boolean isThumbnailColumnSearch = resolvedData.useThumbnailColumn();

		// --- Dispatcher Logic ---

		if ( candidateAsset.hasVehicleId() ) {
			return isOrphanByVehicleId( candidateAsset, searchFileName, isThumbnailColumnSearch );
		}

		if ( candidateAsset.hasVehicleUuid() ) {
			return isOrphanByVehicleUuid( candidateAsset, searchFileName, isThumbnailColumnSearch );
		}

		if ( candidateAsset.hasStandId() ) {
			return isOrphanByStand( candidateAsset, searchFileName, isThumbnailColumnSearch );
		}

		return isOrphanByCompany( candidateAsset, searchFileName, isThumbnailColumnSearch );

	}

	/**
	 * Executes the orphaning check using the Vehicle's numeric ID.
	 *
	 * @param assetData               The asset data DTO.
	 * @param searchFileName          The adjusted filename used for DB query.
	 * @param isThumbnailColumnSearch Flag to select the correct asset column (FILENAME or THUMBNAIL).
	 * @return {@code true} if the asset is orphan.
	 */
	private boolean isOrphanByVehicleId( AssetDataDTO assetData, String searchFileName,
			boolean isThumbnailColumnSearch ) {
		var vehicleId = assetData.getVehicleId().loadId();
		log.debug( "Validating asset against Vehicle ID | vehicleId={}", vehicleId );

		// 1. Check if the Parent Vehicle exists and is active. If not, the asset is an orphan.
		if ( !assetLookupCacheService.vehicleExists( vehicleId ) ) {
			log.debug( "Vehicle ID not found or inactive, asset is an EFS orphan | vehicleId={}", vehicleId );
			return true;
		}

		// 2. The Parent Vehicle exists. Is the Asset linked to it?
		if ( assetLookupCacheService.vehicleAssetLinked(
				vehicleId, searchFileName, isThumbnailColumnSearch ) ) {
			log.debug( "Asset is actively referenced by Vehicle ID, skipping deletion | vehicleId={}", vehicleId );
			return false; // NOT an orphan
		}

		// 3. The Parent Vehicle exists, but the image is not linked (it was removed from the Vehicle's asset list in the DB).
		log.debug(
				"Asset is not referenced by active Vehicle ID, it is an EFS orphan | vehicleId={}", vehicleId );
		return true; // IS an orphan
	}

	private boolean isOrphanByStand( AssetDataDTO assetData, String searchFileName,
			boolean isThumbnailColumnSearch ) {
		if ( assetData.getStandId() == null ) {
			log.warn( "Stand ID is null for asset | assetData={}", assetData );
			return false;
		}
		Integer standId = assetData.getStandId().loadId();
		log.debug( "Validating asset against Stand ID | standId={}", standId );

		if ( !assetLookupCacheService.standExists( standId ) ) {
			log.debug( "Stand ID not found or inactive, asset is an EFS orphan | standId={}", standId );
			return true;
		}
		if ( assetLookupCacheService.standAssetLinked( standId, searchFileName, isThumbnailColumnSearch ) ) {
			log.debug( "Stand asset is referenced by Stand ID, skipping deletion | standId={}", standId );
			return false;
		}
		log.debug( "Stand asset is not referenced by Stand ID, it is an EFS orphan | standId={}", standId );
		return true;
	}

	private boolean isOrphanByCompany( AssetDataDTO assetData, String searchFileName,
			boolean isThumbnailColumnSearch ) {
		if ( assetData.getCompanyId() == null ) {
			log.warn( "Company ID is null for asset | assetData={}", assetData );
			return false;
		}
		Integer companyId = assetData.getCompanyId().loadId();
		log.debug( "Validating asset against Company ID | companyId={}", companyId );

		if ( !assetLookupCacheService.companyExists( companyId ) ) {
			log.debug( "Company ID not found or inactive, asset is an EFS orphan | companyId={}", companyId );
			return true;
		}

		if ( assetLookupCacheService.companyAssetLinked( companyId, searchFileName, isThumbnailColumnSearch ) ) {
			log.debug( "Company asset is referenced by Company ID, skipping deletion | companyId={}", companyId );
			return false;
		}

		log.debug( "Company asset is not referenced by Company ID, it is an EFS orphan | companyId={}", companyId );
		return true;
	}

	/**
	 * Executes the orphaning check using the Vehicle's UUID.
	 *
	 * @param assetData               The asset data DTO.
	 * @param searchFileName          The adjusted filename used for DB query.
	 * @param isThumbnailColumnSearch Flag to select the correct asset column (FILENAME or THUMBNAIL).
	 * @return {@code true} if the asset is orphan.
	 */
	private boolean isOrphanByVehicleUuid( AssetDataDTO assetData, String searchFileName,
			boolean isThumbnailColumnSearch ) {
		var vehicleUuid = assetData.getVehicleUuid();
		log.debug( "Validating asset against Vehicle UUID | vehicleUuid={}", vehicleUuid );

		// 1. Check if the Parent Vehicle exists and is active. If not, the asset is an orphan.
		if ( !assetLookupCacheService.vehicleExists( vehicleUuid ) ) {
			log.debug( "Vehicle UUID not found or inactive, asset is an EFS orphan | vehicleUuid={}", vehicleUuid );
			return true;
		}

		// 2. The Parent Vehicle exists. Is the Asset linked to it?
		if ( assetLookupCacheService.vehicleAssetLinked(
				vehicleUuid, searchFileName, isThumbnailColumnSearch ) ) {
			log.debug( "Asset is actively referenced by Vehicle UUID, skipping deletion | vehicleUuid={}",
					vehicleUuid );
			return false; // NOT an orphan
		}

		// 3. The Parent Vehicle exists, but the image is not linked (it was removed from the Vehicle's asset list in the DB).
		log.debug(
				"Asset is not referenced by active Vehicle UUID, it is an EFS orphan | vehicleUuid={}", vehicleUuid
		);
		return true; // IS an orphan
	}

	private boolean isPersonAssetOrphan( AssetDataDTO candidateAsset ) {

		if ( candidateAsset.getPersonId() == null ) {
			log.warn( "Person ID is null for person asset DTO | candidateAsset={}", candidateAsset );
			return false;
		}

		ResolvedAssetData resolvedData = resolveAndValidateFileName( candidateAsset.getFileName() );
		if ( resolvedData == null ) {
			return false;
		}

		Integer personId = candidateAsset.getPersonId().loadId();

		// 1. Person must exist first. If not, the entire branch (incl. vehicles under it) is orphan.
		if ( !assetLookupCacheService.personExists( personId ) ) {
			log.debug( "Person ID not found or inactive, asset is an EFS orphan | personId={}", personId );
			return true;
		}

		String searchFileName = resolvedData.lookupFileName();
		boolean isThumbnailColumnSearch = resolvedData.useThumbnailColumn();

		// 2. If the path references a vehicle, validate ownership BEFORE the asset link check.
		if ( candidateAsset.hasVehicleId() ) {

			Integer vehicleId = candidateAsset.getVehicleId().loadId();
			if ( !assetLookupCacheService.vehicleBelongsToPerson( vehicleId, personId ) ) {
				log.debug(
						"Vehicle ID no longer belongs to Person ID, asset is an EFS orphan | vehicleId={} | personId={}",
						vehicleId, personId
				);
				return true;
			}

			return isOrphanByVehicleId( candidateAsset, searchFileName, isThumbnailColumnSearch );
		}

		if ( candidateAsset.hasVehicleUuid() ) {
			String vehicleUuid = candidateAsset.getVehicleUuid();

			if ( !assetLookupCacheService.vehicleBelongsToPerson( vehicleUuid, personId ) ) {
				log.debug(
						"Vehicle UUID no longer belongs to Person ID, asset is an EFS orphan | vehicleUuid={} | personId={}",
						vehicleUuid, personId
				);
				return true;
			}

			return isOrphanByVehicleUuid( candidateAsset, searchFileName, isThumbnailColumnSearch );
		}
		// 3. No vehicle in path → standard person profile check.
		return isOrphanByPerson( candidateAsset, searchFileName, isThumbnailColumnSearch );

	}

	private boolean isOrphanByPerson( AssetDataDTO assetData, String searchFileName,
			boolean isThumbnailColumnSearch ) {

		Integer personId = assetData.getPersonId().loadId();

		log.debug( "Validating asset against Person ID | personId={}", personId );

		if ( assetLookupCacheService.personProfileAssetLinked( personId, searchFileName, isThumbnailColumnSearch ) ) {
			log.debug( "Person profile is referenced by Person ID, skipping deletion | personId={}", personId );
			return false;
		}
		log.info( "Person profile is not referenced by Person ID, it is an EFS orphan | personId={}", personId );
		return true;
	}

	/**
	 * Resolves the asset filename and determines which column should be used in the database query.
	 *
	 * @param fileName the filename retrieved directly from EFS
	 * @return a {@link ResolvedAssetData} record containing the adjusted filename and a flag indicating if the search
	 * should be done in the THUMBNAIL column.
	 */
	private ResolvedAssetData resolveAssetData( String fileName ) {
		if ( fileName == null ) {
			log.debug( "Null filename provided during resolution, returning safe defaults." );
			return new ResolvedAssetData( null, false );
		}

		String lowerCaseFileName = fileName.toLowerCase();

		// --- Rules to identify and adjust the filename ---

		// 1. THUMBNAIL Case (t_ or thumb_): The asset is stored with the prefix in the THUMBNAIL column.
		if ( isThumbnailFile( lowerCaseFileName ) ) {
			log.debug( "File identified as THUMBNAIL, searching THUMBNAIL column | fileName={}", fileName );
			return new ResolvedAssetData( fileName, true );
		}

		// 2. MEDIUM Case (m_): This is a derivative of the original file, which is stored in the FILENAME column.
		else if ( lowerCaseFileName.startsWith( "m_" ) ) {
			log.debug( "File identified as MEDIUM, removing 'm_' prefix for FILENAME search | fileName={}", fileName );
			// Remove 'm_' to search for the original file name in the FILENAME column.
			return new ResolvedAssetData( fileName.substring( "m_".length() ), false );
		}

		// 3. ORIGINAL Case (no prefix): Use the filename as is, searching the FILENAME column.
		else {
			log.debug( "File identified as ORIGINAL, searching FILENAME column | fileName={}", fileName );
			return new ResolvedAssetData( fileName, false );
		}
	}

	private ResolvedAssetData resolveAndValidateFileName( String fileName ) {
		ResolvedAssetData resolvedData = resolveAssetData( fileName );
		String searchFileName = resolvedData.lookupFileName();
		boolean isThumbnailColumnSearch = resolvedData.useThumbnailColumn();
		log.debug( "Resolved file name | original={} | lookup={} | column={}", fileName, searchFileName,
				isThumbnailColumnSearch ? "THUMBNAIL" : "FILENAME"
		);

		if ( searchFileName == null ) {
			log.warn( "Could not resolve search file name, skipping deletion | fileName={}", fileName );
			return null;
		}
		return resolvedData;
	}

	/**
	 * Identifies if a given filename corresponds to a thumbnail based on legacy prefixes.
	 *
	 * @param fileName The filename from the EFS.
	 * @return true if the file starts with "thumb_" or "t_", false otherwise.
	 */
	private boolean isThumbnailFile( String fileName ) {
		if ( fileName == null )
			return false;
		return fileName.toLowerCase().startsWith( "thumb_" ) || fileName.toLowerCase().startsWith( "t_" );
	}

	/**
	 * Record to encapsulate the result of file name resolution.
	 *
	 * @param lookupFileName     The adjusted file name to use in the SQL query (without prefixes like t_, m_, etc.).
	 * @param useThumbnailColumn If TRUE, the repository should search the ENGINE_ASSET.THUMBNAIL column. If FALSE (for
	 *                           ORIGINAL and MEDIUM), it should search the ENGINE_ASSET.FILENAME column.
	 */
	private record ResolvedAssetData(String lookupFileName, boolean useThumbnailColumn) {

	}
}
