package pt.piscapisca.b2c.asset.processor;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.Scope;
import pt.piscapisca.b2c.asset.processor.infra.EfsGarbageCollectorS3Properties;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default S3-backed implementation of {@link LogFileSource}.
 * <p>
 * <b>Thread-safety:</b> the AWS SDK v2 {@link S3Client} is thread-safe and this class holds no mutable state, so
 * multiple threads may share a single instance safely.
 * <p>
 * <b>Idempotency model:</b> "processed" state is stored directly on the S3 object as a tag (key configured in
 * {@link EfsGarbageCollectorS3Properties#processedTagKey()}). This avoids the need for an external database and keeps
 * the audit trail next to the artefact itself.
 */
@Slf4j
public class S3LogFileSourceImpl implements LogFileSource, AutoCloseable {

	/**
	 * Matches keys ending with {@code company_<id>_files.txt}. The {@code [^_]+} pattern assumes company IDs never
	 * contain underscores — if that ever changes, switch to a non-greedy {@code (.+?)} anchored on the suffix.
	 */
	private static final Pattern COMPANY_PATTERN = Pattern.compile( "company_([^_]+)_files\\.txt$" );

	/**
	 * Same convention as {@link #COMPANY_PATTERN} but for person log files.
	 */
	private static final Pattern PERSON_PATTERN = Pattern.compile( "person_([^_]+)_files\\.txt$" );

	/**
	 * Hard-cap for the "is processed?" parallel lookup pool shutdown. Kept generous — S3 tagging calls are cheap.
	 */
	private static final int TAGGING_LOOKUP_SHUTDOWN_TIMEOUT_SECONDS = 30;

	// Tag keys written alongside the main "processed" flag. Kept as constants (rather than properties) because they
	// define the audit schema and changing them would break historical traceability.
	private static final String TAG_PROCESSED_AT = "efs-gc-processed-at";

	private static final String TAG_RUN_ID = "efs-gc-run-id";

	private static final String TAG_ORPHAN_ACTION = "efs-gc-orphan-action";

	private final S3Client s3Client;

	private final EfsGarbageCollectorS3Properties props;

	public S3LogFileSourceImpl( S3Client s3Client, EfsGarbageCollectorS3Properties props ) {
		this.s3Client = s3Client;
		this.props = props;
	}

	@Override
	public List<S3LogFile> listPendingFiles( Scope scope, Set<String> idFilter, boolean skipAlreadyProcessed ) {
		String prefix = prefixFor( scope );
		Pattern pattern = scope == Scope.COMPANIES ? COMPANY_PATTERN : PERSON_PATTERN;

		log.debug(
				"Listing S3 log files | scope={} | prefix={} | idFilterSize={} | skipAlreadyProcessed={}", scope,
				prefix, idFilter == null ? 0 : idFilter.size(), skipAlreadyProcessed
		);

		List<S3LogFile> all = listAll( prefix, pattern );
		log.info( "Found {} log file(s) under prefix {}", all.size(), prefix );

		// Client-side ID filter — S3 does not support "prefix + arbitrary substring" natively, so we filter after list.
		// For very large buckets consider partitioning the prefix by ID hash to avoid over-listing.
		List<S3LogFile> filtered = ( idFilter == null || idFilter.isEmpty() )
				? all
				: all.stream().filter( f -> idFilter.contains( f.entityId() ) ).toList();

		if ( idFilter != null && !idFilter.isEmpty() ) {
			log.info( "After ID filter: {} of {} file(s) retained (idFilterSize={})",
					filtered.size(), all.size(), idFilter.size()
			);
		}

		if ( !skipAlreadyProcessed ) {
			log.debug( "skipAlreadyProcessed=false — returning {} file(s) without tag lookup", filtered.size() );
			return filtered;
		}

		return filterOutProcessedInParallel( filtered );
	}

	/**
	 * Paginates through {@code ListObjectsV2} responses and keeps only keys that match {@code pattern}.
	 * <p>
	 * Unrecognised keys are logged at debug level — they typically indicate leftover files or manual uploads, neither
	 * of which should abort the run.
	 */
	private List<S3LogFile> listAll( String prefix, Pattern pattern ) {
		List<S3LogFile> result = new ArrayList<>();
		String continuationToken = null;
		int pages = 0;
		int ignored = 0;

		do {
			ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
					.bucket( props.bucket() )
					.prefix( prefix );
			if ( continuationToken != null ) {
				req.continuationToken( continuationToken );
			}

			var resp = s3Client.listObjectsV2( req.build() );
			pages++;

			for ( S3Object obj : resp.contents() ) {
				Matcher m = pattern.matcher( obj.key() );
				if ( m.find() ) {
					result.add( new S3LogFile( obj.key(), m.group( 1 ), obj.size() ) );
				}
				else {
					ignored++;
					log.debug( "Ignoring unrecognised S3 key: {}", obj.key() );
				}
			}

			continuationToken = Boolean.TRUE.equals( resp.isTruncated() ) ? resp.nextContinuationToken() : null;
		}
		while ( continuationToken != null );

		log.debug( "Finished ListObjectsV2 | prefix={} | pages={} | matched={} | ignored={}",
				prefix, pages, result.size(), ignored
		);
		return result;
	}

	/**
	 * Concurrently calls {@link #isProcessed(S3LogFile)} for every input file and returns only those that are still
	 * pending. Preserves the input ordering — futures are created and drained in order.
	 */
	private List<S3LogFile> filterOutProcessedInParallel( List<S3LogFile> files ) {
		log.debug( "Starting parallel 'is processed?' check | files={} | parallelism={}",
				files.size(), props.taggingLookupParallelism()
		);

		ExecutorService pool = Executors.newFixedThreadPool( props.taggingLookupParallelism() );
		try {
			List<CompletableFuture<S3LogFile>> futures = files.stream()
					.map( f -> CompletableFuture.supplyAsync( () -> isProcessed( f ) ? null : f, pool ) )
					.toList();

			List<S3LogFile> pending = new ArrayList<>( files.size() );
			for ( CompletableFuture<S3LogFile> fut : futures ) {
				// isProcessed swallows exceptions internally, so join() shouldn't throw in practice. If it ever does
				// (e.g. future refactor), we still shut down the pool cleanly in the finally block.
				S3LogFile f = fut.join();
				if ( f != null ) {
					pending.add( f );
				}
			}

			log.info( "Tagging check complete | pending={} | alreadyProcessed={} | total={}",
					pending.size(), files.size() - pending.size(), files.size()
			);
			return pending;
		}
		finally {
			pool.shutdown();
			try {
				if ( !pool.awaitTermination( TAGGING_LOOKUP_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS ) ) {
					log.warn(
							"Tagging lookup pool did not terminate within {}s — forcing shutdownNow",
							TAGGING_LOOKUP_SHUTDOWN_TIMEOUT_SECONDS
					);
					pool.shutdownNow();
				}
			}
			catch ( InterruptedException e ) {
				log.warn( "Interrupted while shutting down tagging lookup pool" );
				Thread.currentThread().interrupt();
				pool.shutdownNow();
			}
		}
	}

	@Override
	public BufferedReader openReader( S3LogFile file ) {
		log.debug( "Opening S3 reader | key={} | sizeBytes={}", file.key(), file.sizeBytes() );

		ResponseInputStream<GetObjectResponse> stream = s3Client.getObject( b -> b
				.bucket( props.bucket() )
				.key( file.key() ) );

		// BufferedReader wraps the S3 stream; closing it will also close the underlying HTTP connection.
		return new BufferedReader(
				new InputStreamReader( stream, StandardCharsets.UTF_8 ),
				props.readBufferSize()
		);
	}

	@Override
	public void markAsProcessed( S3LogFile file, String runId, String orphanAction ) {
		Tagging tagging = Tagging.builder()
				.tagSet(
						Tag.builder().key( props.processedTagKey() ).value( "true" ).build(),
						Tag.builder().key( TAG_PROCESSED_AT ).value( Instant.now().toString() ).build(),
						Tag.builder().key( TAG_RUN_ID ).value( runId ).build(),
						Tag.builder().key( TAG_ORPHAN_ACTION ).value( orphanAction ).build()
				)
				.build();

		s3Client.putObjectTagging( req -> req
				.bucket( props.bucket() )
				.key( file.key() )
				.tagging( tagging )
		);

		log.info( "Marked S3 object as processed | key={} | runId={} | orphanAction={}",
				file.key(), runId, orphanAction
		);
	}

	@Override
	public boolean isProcessed( S3LogFile file ) {
		try {
			GetObjectTaggingResponse resp = s3Client.getObjectTagging( b -> b
					.bucket( props.bucket() )
					.key( file.key() ) );

			boolean processed = resp.tagSet().stream()
					.anyMatch( t -> props.processedTagKey().equals( t.key() ) && "true".equalsIgnoreCase( t.value() ) );

			log.trace( "Tag lookup | key={} | processed={}", file.key(), processed );
			return processed;
		}
		catch ( Exception e ) {
			// Fail-open: any error → treat as NOT processed. Re-processing is idempotent, silently skipping is not.
			log.warn( "Failed to read tags for {} — assuming NOT processed | {}", file.key(), e.getMessage() );
			return false;
		}
	}

	private String prefixFor( Scope scope ) {
		return scope == Scope.COMPANIES ? props.companiesPrefix() : props.personsPrefix();
	}

	@Override public void close() throws Exception {
		if ( s3Client != null )
			s3Client.close();
	}
}
