package pt.piscapisca.b2c.asset.processor.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;

import static org.jooq.impl.DSL.field;

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

	private Field<String> getAssetField( boolean isThumbnail ) {
		String columnName = isThumbnail ? "thumbnail" : "filename";
		return field( columnName, String.class );
	}

	private Field<Integer> jsonIdAsInt( Field<?> jsonField ) {
		return field(
				"CASE WHEN ({0} ->> 'id') ~ '^[0-9]+$' THEN ({0} ->> 'id')::int END",
				Integer.class,
				jsonField
		);
	}

	private boolean existsJsonAssetByEntityId( String tableName,
			String entityIdColumnName,
			Integer entityId,
			String jsonColumnName,
			String fileName,
			boolean isThumbnail ) {

		if ( entityId == null || fileName == null || fileName.isBlank() ) {
			return false;
		}

		Field<Object> jsonField = field( jsonColumnName, Object.class );
		Field<Integer> entityIdField = field( entityIdColumnName, Integer.class );

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
