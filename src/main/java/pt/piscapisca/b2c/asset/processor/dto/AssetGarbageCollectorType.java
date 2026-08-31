package pt.piscapisca.b2c.asset.processor.dto;

public enum AssetGarbageCollectorType {
	COMPANY,
	PERSON,
	UNKNOWN;

	public static AssetGarbageCollectorType fromPath( String path ) {
		String normalized = path.toLowerCase();
		if ( normalized.contains( "/companies/" ) )
			return COMPANY;
		if ( normalized.contains( "/persons/" ) || normalized.contains( "/persons_" ) )
			return PERSON;
		return UNKNOWN;
	}
}
