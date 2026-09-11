package pt.piscapisca.b2c.asset.processor.infra.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Result;

import java.util.HashSet;
import java.util.Set;

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

	/**
	 * Immutable holder for the set of asset filenames and thumbnails linked to a single
	 * domain entity (vehicle, stand, company or person). Loaded once per entity so that
	 * per-line orphan checks become in-memory set lookups instead of one SQL query per line
	 * (eliminates the N+1 query pattern).
	 */
	public record AssetNames( Set<String> filenames, Set<String> thumbnails ) {

		public static AssetNames empty() {
			return new AssetNames( Set.of(), Set.of() );
		}

		/**
		 * Membership check equivalent to the previous {@code assetField.eq( name )} predicate.
		 *
		 * @param name        the filename or thumbnail name to look up
		 * @param isThumbnail whether to look up against the thumbnail set instead of the filename set
		 * @return {@code true} if the name is present in the corresponding set
		 */
		public boolean contains( String name, boolean isThumbnail ) {
			if ( name == null || name.isBlank() ) {
				return false;
			}
			return ( isThumbnail ? thumbnails : filenames ).contains( name );
		}

		/**
		 * Returns a new {@link AssetNames} combining this instance with {@code other}.
		 */
		public AssetNames merge( AssetNames other ) {
			if ( other == null ) {
				return this;
			}
			Set<String> mergedFilenames = new HashSet<>( this.filenames );
			mergedFilenames.addAll( other.filenames );
			Set<String> mergedThumbnails = new HashSet<>( this.thumbnails );
			mergedThumbnails.addAll( other.thumbnails );
			return new AssetNames( mergedFilenames, mergedThumbnails );
		}
	}

	/**
	 * Loads all filenames/thumbnails of active vehicle assets for a given vehicle ID in a single query.
	 * Behaviour-equivalent to {@link #existsActiveVehicleAssetByVehicleId(String, Integer, boolean)} but
	 * loads the whole set once instead of issuing one EXISTS query per candidate line.
	 */
	public AssetNames loadActiveVehicleAssetNamesByVehicleId( Integer vehicleId ) {
		if ( vehicleId == null ) {
			return AssetNames.empty();
		}

		log.trace( "Loading active vehicle asset names | vehicleId={}", vehicleId );

		Result<Record2<String, String>> rows = dsl.select(
					field( ENGINE_ASSET + ".filename", String.class ),
					field( ENGINE_ASSET + ".thumbnail", String.class ) )
				.from( ENGINE_ASSET )
				.innerJoin( ENGINE_VEHICLE_ASSET )
				.on( field( ENGINE_VEHICLE_ASSET + ".asset_id" )
						.eq( field( ENGINE_ASSET + ".id" ) ) )
				.innerJoin( ENGINE_VEHICLE ).on( field( ENGINE_VEHICLE + ".id" )
						.eq( field( ENGINE_VEHICLE_ASSET + ".vehicle_id" ) ) )
				.where( field( ENGINE_VEHICLE + ".id" ).eq( vehicleId ) )
				.and( field( ENGINE_VEHICLE + ".active" ).isTrue() )
				.fetch();

		return toAssetNames( rows );
	}

	/**
	 * Loads all filenames/thumbnails of active vehicle assets for a given vehicle UUID in a single query.
	 * Behaviour-equivalent to {@link #existsActiveVehicleAssetByVehicleUuid(String, String, boolean)}.
	 */
	public AssetNames loadActiveVehicleAssetNamesByVehicleUuid( String vehicleUuid ) {
		if ( vehicleUuid == null || vehicleUuid.isBlank() ) {
			return AssetNames.empty();
		}

		log.trace( "Loading active vehicle asset names | vehicleUuid={}", vehicleUuid );

		Result<Record2<String, String>> rows = dsl.select(
					field( ENGINE_ASSET + ".filename", String.class ),
					field( ENGINE_ASSET + ".thumbnail", String.class ) )
				.from( ENGINE_ASSET )
				.innerJoin( ENGINE_VEHICLE_ASSET )
				.on( field( ENGINE_VEHICLE_ASSET + ".asset_id" )
						.eq( field( ENGINE_ASSET + ".id" ) ) )
				.innerJoin( ENGINE_VEHICLE ).on( field( ENGINE_VEHICLE + ".id" )
						.eq( field( ENGINE_VEHICLE_ASSET + ".vehicle_id" ) ) )
				.where( field( ENGINE_VEHICLE + ".vuuid" ).eq( vehicleUuid ) )
				.and( field( ENGINE_VEHICLE + ".active" ).isTrue() )
				.fetch();

		return toAssetNames( rows );
	}

	/**
	 * Loads all filenames/thumbnails of stand assets for a given stand ID in a single query.
	 * Behaviour-equivalent to {@link #existsStandAssetByStandId(String, Integer, boolean)}.
	 */
	public AssetNames loadStandAssetNamesByStandId( Integer standId ) {
		if ( standId == null ) {
			return AssetNames.empty();
		}

		log.trace( "Loading stand asset names | standId={}", standId );

		Result<Record2<String, String>> rows = dsl.select(
					field( ENGINE_ASSET + ".filename", String.class ),
					field( ENGINE_ASSET + ".thumbnail", String.class ) )
				.from( ENGINE_ASSET )
				.innerJoin( ENGINE_STAND_ASSET ).on( field( ENGINE_STAND_ASSET + ".asset_id" )
						.eq( field( ENGINE_ASSET + ".id" ) ) )
				.where( field( ENGINE_STAND_ASSET + ".stand_id" ).eq( standId ) )
				.fetch();

		return toAssetNames( rows );
	}

	/**
	 * Loads company logo and logo_type asset names for a given company ID.
	 * Combines both JSON columns, mirroring the previous pair of EXISTS checks
	 * ({@link #existsCompanyLogoAsset} / {@link #existsCompanyLogoTypeAsset}).
	 */
	public AssetNames loadCompanyAssetNames( Integer companyId ) {
		AssetNames logo = loadJsonAssetNames( ENGINE_COMPANY, "id", companyId, "logo" );
		AssetNames logoType = loadJsonAssetNames( ENGINE_COMPANY, "id", companyId, "logo_type" );
		return logo.merge( logoType );
	}

	/**
	 * Loads person profile asset names for a given person ID.
	 * Behaviour-equivalent to {@link #existsPersonProfileAsset(Integer, String, boolean)}.
	 */
	public AssetNames loadPersonProfileAssetNames( Integer personId ) {
		return loadJsonAssetNames( ENGINE_PERSON, "id", personId, "image_profile" );
	}

	/**
	 * Generic helper that loads asset names referenced inside an entity JSON column.
	 */
	private AssetNames loadJsonAssetNames( String tableName,
			String entityIdColumnName,
			Integer entityId,
			String jsonColumnName ) {

		if ( entityId == null ) {
			return AssetNames.empty();
		}

		Field<Object> jsonField = field( tableName + "." + jsonColumnName, Object.class );
		Field<Integer> entityIdField = field( tableName + "." + entityIdColumnName, Integer.class );
		Field<Integer> assetId = jsonIdAsInt( jsonField );

		Result<Record2<String, String>> rows = dsl.select(
					field( ENGINE_ASSET + ".filename", String.class ),
					field( ENGINE_ASSET + ".thumbnail", String.class ) )
				.from( tableName )
				.join( ENGINE_ASSET ).on( field( ENGINE_ASSET + ".id" ).eq( assetId ) )
				.where( entityIdField.eq( entityId ) )
				.and( jsonField.isNotNull() )
				.fetch();

		return toAssetNames( rows );
	}

	/**
	 * Converts a two-column (filename, thumbnail) result set into an {@link AssetNames} holder,
	 * skipping null/blank values.
	 */
	private AssetNames toAssetNames( Result<Record2<String, String>> rows ) {
		Set<String> filenames = new HashSet<>();
		Set<String> thumbnails = new HashSet<>();
		for ( Record2<String, String> row : rows ) {
			String filename = row.value1();
			String thumbnail = row.value2();
			if ( filename != null && !filename.isBlank() ) {
				filenames.add( filename );
			}
			if ( thumbnail != null && !thumbnail.isBlank() ) {
				thumbnails.add( thumbnail );
			}
		}
		return new AssetNames( filenames, thumbnails );
	}
}
