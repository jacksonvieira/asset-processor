package pt.piscapisca.b2c.asset.processor.infra.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;

import static org.jooq.impl.DSL.field;

/**
 * Repository responsible for querying asset existence and relationships across multiple
 * domain entities (vehicles, stands, companies, and persons) using jOOQ.
 */
@Slf4j
@RequiredArgsConstructor
public class AssetRepository {

	private final DSLContext dsl;

	public static final String ENGINE_ASSET = "asset";

	public static final String ENGINE_VEHICLE_ASSET = "vehicle_asset";

	public static final String ENGINE_VEHICLE = "vehicle";

	public static final String ENGINE_STAND_ASSET = "stand_asset";

	public static final String ENGINE_COMPANY = "company";

	public static final String ENGINE_PERSON = "person";

	/**
	 * Checks if an active vehicle asset exists linked to a specific vehicle ID.
	 *
	 * @param searchFileName          the filename or thumbnail name to check
	 * @param vehicleId               the unique vehicle identifier
	 * @param isThumbnailColumnSearch whether to check against the thumbnail column instead of the filename
	 * @return {@code true} if an active matching record exists, {@code false} otherwise
	 */
	public boolean existsActiveVehicleAssetByVehicleId( String searchFileName, Integer vehicleId,
			boolean isThumbnailColumnSearch ) {
		if ( searchFileName == null || searchFileName.isBlank() || vehicleId == null ) {
			return false;
		}

		log.trace( "Checking if active vehicle asset exists | fileName={} | vehicleId={} | isThumbnail={}",
				searchFileName, vehicleId, isThumbnailColumnSearch );

		Field<String> assetField = getAssetField( isThumbnailColumnSearch );
		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_ASSET )
						.innerJoin( ENGINE_VEHICLE_ASSET )
						.on( field( ENGINE_VEHICLE_ASSET + ".asset_id" )
								.eq( field( ENGINE_ASSET + ".id" ) ) )
						.innerJoin( ENGINE_VEHICLE ).on( field( ENGINE_VEHICLE + ".id" )
								.eq( field( ENGINE_VEHICLE_ASSET + ".vehicle_id" ) ) )
						.where( field( ENGINE_VEHICLE + ".id" ).eq( vehicleId ) )
						.and( field( ENGINE_VEHICLE + ".active" ).isTrue() )
						.and( assetField.eq( searchFileName ) )
		);
	}

	/**
	 * Checks if a stand asset exists linked to a specific stand ID.
	 *
	 * @param fileName    the filename or thumbnail name to check
	 * @param standId     the unique stand identifier
	 * @param isThumbnail whether to check against the thumbnail column
	 * @return {@code true} if a matching record exists, {@code false} otherwise
	 */
	public boolean existsStandAssetByStandId( String fileName, Integer standId, boolean isThumbnail ) {
		if ( fileName == null || fileName.isBlank() || standId == null ) {
			return false;
		}

		log.trace( "Checking if stand asset exists | fileName={} | standId={} | isThumbnail={}",
				fileName, standId, isThumbnail );

		Field<String> assetField = getAssetField( isThumbnail );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_ASSET )
						.innerJoin( ENGINE_STAND_ASSET ).on( field( ENGINE_STAND_ASSET + ".asset_id" )
								.eq( field( ENGINE_ASSET + ".id" ) ) )
						.where( field( ENGINE_STAND_ASSET + ".stand_id" ).eq( standId ) )
						.and( assetField.eq( fileName ) )
		);
	}

	/**
	 * Checks if a company logo asset exists for a given company ID.
	 *
	 * @param companyId   the unique company identifier
	 * @param fileName    the filename to check
	 * @param isThumbnail whether to check against the thumbnail column
	 * @return {@code true} if the logo asset exists, {@code false} otherwise
	 */
	public boolean existsCompanyLogoAsset( Integer companyId, String fileName, boolean isThumbnail ) {
		return existsJsonAssetByEntityId(
				ENGINE_COMPANY,
				"id",
				companyId,
				"logo",
				fileName,
				isThumbnail
		);
	}

	/**
	 * Checks if a company logo type asset exists for a given company ID.
	 *
	 * @param companyId   the unique company identifier
	 * @param fileName    the filename to check
	 * @param isThumbnail whether to check against the thumbnail column
	 * @return {@code true} if the logo type asset exists, {@code false} otherwise
	 */
	public boolean existsCompanyLogoTypeAsset( Integer companyId, String fileName, boolean isThumbnail ) {
		return existsJsonAssetByEntityId(
				ENGINE_COMPANY,
				"id",
				companyId,
				"logo_type",
				fileName,
				isThumbnail
		);
	}

	/**
	 * Checks if an active vehicle asset exists linked to a specific vehicle UUID.
	 *
	 * @param fileName    the filename or thumbnail name to check
	 * @param vehicleUuid the vehicle UUID string
	 * @param isThumbnail whether to check against the thumbnail column
	 * @return {@code true} if an active matching record exists, {@code false} otherwise
	 */
	public boolean existsActiveVehicleAssetByVehicleUuid( String fileName, String vehicleUuid, boolean isThumbnail ) {
		if ( fileName == null || fileName.isBlank() || vehicleUuid == null || vehicleUuid.isBlank() ) {
			return false;
		}

		log.trace( "Checking if active vehicle asset exists by uuid | fileName={} | vehicleUuid={} | isThumbnail={}",
				fileName, vehicleUuid, isThumbnail );

		Field<String> assetField = getAssetField( isThumbnail );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( ENGINE_ASSET )
						.innerJoin( ENGINE_VEHICLE_ASSET )
						.on( field( ENGINE_VEHICLE_ASSET + ".asset_id" )
								.eq( field( ENGINE_ASSET + ".id" ) ) )
						.innerJoin( ENGINE_VEHICLE ).on( field( ENGINE_VEHICLE + ".id" )
								.eq( field( ENGINE_VEHICLE_ASSET + ".vehicle_id" ) ) )
						.where( field( ENGINE_VEHICLE + ".vuuid" ).eq( vehicleUuid ) )
						.and( field( ENGINE_VEHICLE + ".active" ).isTrue() )
						.and( assetField.eq( fileName ) )
		);
	}

	/**
	 * Checks if a person profile asset exists for a given person ID.
	 *
	 * @param personId    the unique person identifier
	 * @param fileName    the filename to check
	 * @param isThumbnail whether to check against the thumbnail column
	 * @return {@code true} if the profile asset exists, {@code false} otherwise
	 */
	public boolean existsPersonProfileAsset( Integer personId, String fileName, boolean isThumbnail ) {
		return existsJsonAssetByEntityId(
				ENGINE_PERSON,
				"id",
				personId,
				"image_profile",
				fileName,
				isThumbnail
		);
	}

	/**
	 * Resolves the target database field based on whether a thumbnail or standard filename is requested.
	 *
	 * @param isThumbnail if true, returns the {@code thumbnail} field; otherwise returns {@code filename}
	 * @return the corresponding string {@link Field}
	 */
	private Field<String> getAssetField( boolean isThumbnail ) {
		String columnName = isThumbnail ? "thumbnail" : "filename";
		return field( ENGINE_ASSET + "." + columnName, String.class );
	}

	/**
	 * Safely extracts an integer ID from a JSON column field using a regular expression check.
	 *
	 * @param jsonField the jOOQ field representing the JSON column data
	 * @return an integer field containing the extracted ID or null
	 */
	private Field<Integer> jsonIdAsInt( Field<?> jsonField ) {
		return field(
				"CASE WHEN ({0} ->> 'id') ~ '^[0-9]+$' THEN ({0} ->> 'id')::int END",
				Integer.class,
				jsonField
		);
	}

	/**
	 * Generic helper to check asset existence linked inside entity JSON columns.
	 *
	 * @param tableName          the database table name
	 * @param entityIdColumnName the primary key column name of the entity table
	 * @param entityId           the entity ID value
	 * @param jsonColumnName     the JSON column name containing asset references
	 * @param fileName           the filename to check
	 * @param isThumbnail        whether to check against the thumbnail column
	 * @return {@code true} if the asset exists in the JSON reference, {@code false} otherwise
	 */
	private boolean existsJsonAssetByEntityId( String tableName,
			String entityIdColumnName,
			Integer entityId,
			String jsonColumnName,
			String fileName,
			boolean isThumbnail ) {

		if ( entityId == null || fileName == null || fileName.isBlank() ) {
			return false;
		}

		Field<Object> jsonField = field( tableName + "." + jsonColumnName, Object.class );
		Field<Integer> entityIdField = field( tableName + "." + entityIdColumnName, Integer.class );

		Field<Integer> assetId = jsonIdAsInt( jsonField );
		Field<String> assetField = getAssetField( isThumbnail );

		return dsl.fetchExists(
				dsl.selectOne()
						.from( tableName )
						.join( ENGINE_ASSET ).on( field( ENGINE_ASSET + ".id" ).eq( assetId ) )
						.where( entityIdField.eq( entityId ) )
						.and( jsonField.isNotNull() )
						.and( assetField.eq( fileName ) )
		);
	}
}
