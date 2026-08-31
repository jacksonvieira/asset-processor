package pt.piscapisca.b2c.asset.processor;

import java.util.List;

public class EfsPathFilter {

	private final List< String > allowedPrefixes;

	public EfsPathFilter( List< String > allowedPrefixes ) {
		this.allowedPrefixes = allowedPrefixes;
	}

	public boolean shouldProcess( String line ) {
		String normalized = line.toLowerCase();
		return allowedPrefixes.stream().anyMatch( normalized::startsWith );
	}

	public static EfsPathFilter defaultFilter() {
		return new EfsPathFilter( List.of(
				"/efs/images/companies/",
				"/efs/images/persons/",
				"/efs/images/persons_"
		) );
	}
}
