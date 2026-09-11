package pt.piscapisca.b2c.asset.processor.infra.statistics;

import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Thread-safe aggregator of per-entity processing statistics across an EFS GC run.
 * <p>
 * Uses {@link ConcurrentHashMap} + {@link LongAdder} — both designed for high-concurrency write paths —
 * so multiple virtual threads can record observations simultaneously without lock contention.
 * <p>
 * <b>Lifecycle</b> (managed by
 * {@link pt.piscapisca.b2c.asset.processor.service.EfsGarbageCollectorService}):
 * <ol>
 *   <li>Call {@link #reset()} once at the beginning of each run to clear any residual state.</li>
 *   <li>Workers call {@link #recordAnalyzed}, {@link #recordToKeep}, {@link #recordToRemove}
 *       concurrently throughout the run — all operations are non-blocking.</li>
 *   <li>Call {@link #logConsolidatedReport(String, boolean)} once after all files have finished
 *       to emit the per-entity summary.</li>
 * </ol>
 * <p>
 * <b>Thread-safety:</b> all public methods are safe to call from any number of concurrent threads.
 * {@link #logConsolidatedReport} reads final sums and is meant to be called after all workers finish,
 * but will still produce consistent per-entity values even if called concurrently (individual
 * {@link LongAdder#sum()} calls are atomic).
 */
@Slf4j
public class ProcessingStatisticsCollector {

	// -------------------------------------------------------------------------
	// Internal value holder — one instance per distinct entity (company / person)
	// -------------------------------------------------------------------------

	private static final class EntityStats {

		final String label;
		final String entityType;
		final LongAdder analyzed = new LongAdder();
		final LongAdder toRemove = new LongAdder();
		final LongAdder toKeep = new LongAdder();

		EntityStats( String label, String entityType ) {
			this.label = label;
			this.entityType = entityType;
		}
	}

	// -------------------------------------------------------------------------
	// State
	// -------------------------------------------------------------------------

	private final ConcurrentHashMap<String, EntityStats> statsMap = new ConcurrentHashMap<>();

	// -------------------------------------------------------------------------
	// Lifecycle
	// -------------------------------------------------------------------------

	/**
	 * Clears all accumulated counters. Must be called once at the beginning of every run to avoid
	 * mixing statistics from previous executions.
	 */
	public void reset() {
		statsMap.clear();
	}

	// -------------------------------------------------------------------------
	// Recording
	// -------------------------------------------------------------------------

	/**
	 * Records one path as analysed for the given entity. Called for every successfully parsed asset,
	 * regardless of whether it is orphan or not.
	 *
	 * @param key        stable unique key for the entity (e.g. {@code "COMPANY_12345"})
	 * @param label      human-readable label for log output (e.g. {@code "Company 12345"})
	 * @param entityType entity type string matching the enum name (e.g. {@code "COMPANY"})
	 */
	public void recordAnalyzed( String key, String label, String entityType ) {
		getOrCreate( key, label, entityType ).analyzed.increment();
	}

	/**
	 * Records one path as kept — the asset is still referenced in the DB and will not be actioned.
	 *
	 * @param key        stable unique key for the entity
	 * @param label      human-readable label for log output
	 * @param entityType entity type string matching the enum name
	 */
	public void recordToKeep( String key, String label, String entityType ) {
		getOrCreate( key, label, entityType ).toKeep.increment();
	}

	/**
	 * Records one path as scheduled for removal — either the orphan action succeeded, or dry-run
	 * confirmed it would have been applied.
	 *
	 * @param key        stable unique key for the entity
	 * @param label      human-readable label for log output
	 * @param entityType entity type string matching the enum name
	 */
	public void recordToRemove( String key, String label, String entityType ) {
		getOrCreate( key, label, entityType ).toRemove.increment();
	}

	// -------------------------------------------------------------------------
	// Reporting
	// -------------------------------------------------------------------------

	/**
	 * Emits one {@code log.info} line per entity (grouped by COMPANY then PERSON, sorted by label
	 * for deterministic output), followed by a totals line.
	 * <p>
	 * Safe to call from any thread once all workers have finished.
	 *
	 * @param runId  run identifier for log correlation
	 * @param dryRun {@code true} if this was a dry-run execution (changes Portuguese verb tense in
	 *               the output — "seriam removidos" vs "serão removidos")
	 */
	public void logConsolidatedReport( String runId, boolean dryRun ) {
		if ( statsMap.isEmpty() ) {
			log.info( "Relatório consolidado | runId={} | nenhuma entidade processada", runId );
			return;
		}

		String removeLabel = dryRun ? "seriam removidos" : "serão removidos";

		List<EntityStats> companies = statsMap.values().stream()
				.filter( s -> "COMPANY".equals( s.entityType ) )
				.sorted( Comparator.comparing( s -> s.label ) )
				.collect( Collectors.toList() );

		List<EntityStats> persons = statsMap.values().stream()
				.filter( s -> "PERSON".equals( s.entityType ) )
				.sorted( Comparator.comparing( s -> s.label ) )
				.collect( Collectors.toList() );

		long totalAnalyzed = 0;
		long totalRemove = 0;
		long totalKeep = 0;

		log.info( "========== RELATÓRIO CONSOLIDADO | runId={} ==========", runId );

		if ( !companies.isEmpty() ) {
			log.info( "--- Companies ({}) ---", companies.size() );
			for ( EntityStats stats : companies ) {
				long a = stats.analyzed.sum();
				long r = stats.toRemove.sum();
				long k = stats.toKeep.sum();
				totalAnalyzed += a;
				totalRemove += r;
				totalKeep += k;
				log.info( "{} analisou {} ficheiros, {} {} e {} continuarão | runId={}",
						stats.label, a, r, removeLabel, k, runId );
			}
		}

		if ( !persons.isEmpty() ) {
			log.info( "--- Persons ({}) ---", persons.size() );
			for ( EntityStats stats : persons ) {
				long a = stats.analyzed.sum();
				long r = stats.toRemove.sum();
				long k = stats.toKeep.sum();
				totalAnalyzed += a;
				totalRemove += r;
				totalKeep += k;
				log.info( "{} analisou {} ficheiros, {} {} e {} continuarão | runId={}",
						stats.label, a, r, removeLabel, k, runId );
			}
		}

		log.info( "TOTAL | analisados={} | {}={} | continuarão={} | runId={}",
				totalAnalyzed, removeLabel, totalRemove, totalKeep, runId );
		log.info( "======================================================" );
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private EntityStats getOrCreate( String key, String label, String entityType ) {
		return statsMap.computeIfAbsent( key, k -> new EntityStats( label, entityType ) );
	}
}
