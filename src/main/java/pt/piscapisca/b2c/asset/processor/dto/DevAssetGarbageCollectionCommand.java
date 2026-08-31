package pt.piscapisca.b2c.asset.processor.dto;

import picocli.CommandLine.Option;
import pt.piscapisca.b2c.hashids.SecretId;

import java.util.HashSet;
import java.util.Set;

public class DevAssetGarbageCollectionCommand {

	@Option( names = { "--scope" }, description = "Scope: COMPANIES or PERSONS", required = true )
	private Scope scope;

	@Option( names = { "--ids" }, description = "Comma-separated list of IDs", split = "," )
	private Set<SecretId> ids = new HashSet<>();

	@Option( names = { "--skip-processed" }, description = "Skip already processed files", defaultValue = "false" )
	private boolean skipAlreadyProcessed;

	@Option( names = { "--mode" }, description = "Processing mode: SEQUENTIAL or PARALLEL_FILES", required = true )
	private ProcessingMode mode;

	@Option( names = { "--parallel-files" }, description = "Number of parallel files (optional)" )
	private Integer parallelFiles;

	@Option( names = {
			"--orphan-action" }, description = "Orphan action: MOVE_TO_QUARANTINE, DELETE, or WRITE_TO_REMOVE_FILE", required = true )
	private OrphanAction orphanAction;

	@Option( names = { "--dry-run" }, description = "Dry run mode (true/false)", defaultValue = "true" )
	private boolean dryRun;

	// Validações pós-carregamento do Picocli
	public void validate() {
		if ( scope == null )
			throw new IllegalArgumentException( "scope is required" );
		if ( mode == null )
			throw new IllegalArgumentException( "mode is required" );
		if ( orphanAction == null )
			throw new IllegalArgumentException( "orphanAction is required" );

		if ( mode == ProcessingMode.SEQUENTIAL && parallelFiles != null ) {
			throw new IllegalArgumentException( "parallelFiles is only valid for PARALLEL_FILES mode" );
		}
		if ( parallelFiles != null && parallelFiles <= 0 ) {
			throw new IllegalArgumentException( "parallelFiles must be > 0 when provided" );
		}

		ids = ( ids == null ) ? Set.of() : Set.copyOf( ids );
	}

	// Getters
	public Scope scope() {
		return scope;
	}

	public Set<SecretId> ids() {
		return ids;
	}

	public boolean skipAlreadyProcessed() {
		return skipAlreadyProcessed;
	}

	public ProcessingMode mode() {
		return mode;
	}

	public Integer parallelFiles() {
		return parallelFiles;
	}

	public OrphanAction orphanAction() {
		return orphanAction;
	}

	public boolean dryRun() {
		return dryRun;
	}

	public enum Scope {COMPANIES, PERSONS}

	public enum ProcessingMode {SEQUENTIAL, PARALLEL_FILES}

	public enum OrphanAction {MOVE_TO_QUARANTINE, DELETE, WRITE_TO_REMOVE_FILE}
}
