package pt.piscapisca.b2c.asset.processor.executor;

import lombok.extern.slf4j.Slf4j;
import pt.piscapisca.b2c.asset.processor.dto.DevAssetGarbageCollectionCommand.OrphanAction;
import pt.piscapisca.b2c.asset.processor.execution.EfsFileWorker;
import pt.piscapisca.b2c.asset.processor.executor.OrphanActionExecutor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runtime registry that maps each {@link OrphanAction} to its {@link OrphanActionExecutor} implementation.
 * <p>
 * Populated automatically at startup: Spring injects every {@link OrphanActionExecutor} bean present in the context,
 * and this class indexes them by {@link OrphanActionExecutor#supports()}. Adding a new action therefore requires only a
 * new executor bean — no change to the registry, dispatcher, or any {@code switch}.
 * <p>
 * <b>Fail-fast guarantees:</b>
 * <ul>
 *   <li>the constructor rejects an empty executor list (misconfiguration / profile filtering);</li>
 *   <li>the constructor rejects two executors declaring the same {@link OrphanAction} (ambiguous dispatch);</li>
 *   <li>{@link #get(OrphanAction)} rejects unknown actions with a clear message.</li>
 * </ul>
 * <p>
 * <b>Thread-safety:</b> the internal map is immutable ({@link Collectors#toUnmodifiableMap}) and populated once at
 * construction time — safe for concurrent access from every {@link EfsFileWorker} thread.
 */
@Slf4j
public class OrphanActionExecutorRegistry {

	private final Map<OrphanAction, OrphanActionExecutor> executors;

	public OrphanActionExecutorRegistry( List<OrphanActionExecutor> executors ) {
		if ( executors == null || executors.isEmpty() ) {
			throw new IllegalStateException(
					"No OrphanActionExecutor beans were discovered — the EFS garbage collector cannot dispatch any "
							+ "action. Check component scanning and active profiles." );
		}

		this.executors = executors.stream()
				.collect( Collectors.toUnmodifiableMap(
						OrphanActionExecutor::supports,
						Function.identity(),
						// Two executors claim the same OrphanAction → ambiguous dispatch. Fail loudly at startup
						// with both class names so the offender is obvious.
						( a, b ) -> {
							throw new IllegalStateException( String.format(
									"Duplicate OrphanActionExecutor for action %s: %s and %s",
									a.supports(), a.getClass().getName(), b.getClass().getName()
							) );
						}
				) );

		// Startup visibility: makes it trivial to confirm during deploy which actions are actually wired.
		log.info( "OrphanActionExecutorRegistry initialised | actions={}", this.executors.keySet() );
	}

	/**
	 * Returns the executor bound to {@code action}.
	 *
	 * @throws IllegalStateException if no executor is registered for the given action — this indicates either a bug in
	 *                               the caller (asking for an action that was never wired) or a misconfigured context
	 *                               (executor bean missing / excluded by profile)
	 */
	public OrphanActionExecutor get( OrphanAction action ) {
		OrphanActionExecutor exec = executors.get( action );
		if ( exec == null ) {
			throw new IllegalStateException( String.format(
					"No OrphanActionExecutor registered for %s. Registered actions: %s",
					action, executors.keySet()
			) );
		}
		return exec;
	}
}
