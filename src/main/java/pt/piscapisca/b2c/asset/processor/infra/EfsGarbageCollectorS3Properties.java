package pt.piscapisca.b2c.asset.processor.infra;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record EfsGarbageCollectorS3Properties(

		@NotBlank String bucket,

		@NotBlank String companiesPrefix,

		@NotBlank String personsPrefix,

		@Positive int readBufferSize,

		@Positive int apiCallAttemptTimeoutSeconds,

		@NotBlank String processedTagKey,

		@Min( 1 ) int taggingLookupParallelism
) {

}
