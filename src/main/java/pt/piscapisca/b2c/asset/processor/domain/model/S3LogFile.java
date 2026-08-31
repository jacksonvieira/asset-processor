package pt.piscapisca.b2c.asset.processor.domain.model;

/**
 * Represents a log file stored in S3 that lists EFS paths belonging to a single company or person. Being a
 * {@code record}, this type is immutable and safe to share across threads.
 *
 * @param key       full S3 object key, including any prefix (e.g. {@code "efs_logs_companies/company_0j_files.txt"})
 * @param entityId  identifier extracted from the file name — the company or person UUID/short-id (e.g. {@code "0j"})
 * @param sizeBytes object size in bytes, as reported by S3 {@code ListObjectsV2}
 */
public record S3LogFile(String key, String entityId, long sizeBytes) {

	/**
	 * Returns the last path segment of {@link #key} — i.e. the file name without any S3 prefix.
	 * <p>
	 * S3 keys always use {@code '/'} as a separator regardless of the client OS, so this method is portable.
	 *
	 * @return the substring after the last {@code '/'}, or the full key if none is present
	 */
	public String fileName() {
		int slash = key.lastIndexOf( '/' );
		return slash < 0 ? key : key.substring( slash + 1 );
	}
}
