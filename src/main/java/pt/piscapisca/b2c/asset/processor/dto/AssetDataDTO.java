package pt.piscapisca.b2c.asset.processor.dto;

import lombok.Builder;
import lombok.Value;
import pt.piscapisca.b2c.hashids.SecretId;

import java.io.Serial;
import java.io.Serializable;

@Builder
@Value
public class AssetDataDTO implements Serializable {

	@Serial
	private static final long serialVersionUID = 1384119786684213186L;

	private AssetGarbageCollectorType assetType;

	// IDs de Company/Stand/Vehicle (Relevantes para AssetType.COMPANY)
	private SecretId companyId;

	private SecretId standId;

	private SecretId vehicleId;

	// IDs de Person/Relacionamento (Relevantes para AssetType.PERSON)
	private SecretId personId;
	// private final SecretId resumeId; // Exemplo para o PDF do currículo

	// UUID (Comum a ambos, se extraído do nome do arquivo)
	private String vehicleUuid;

	private String fileName;

	public boolean hasVehicleId() {
		return vehicleId != null;
	}

	public boolean hasVehicleUuid() {
		return vehicleUuid != null && !vehicleUuid.isBlank();
	}

	public boolean hasStandId() {
		return standId != null;
	}

	public boolean hasCompanyId() {
		return companyId != null;
	}
}
