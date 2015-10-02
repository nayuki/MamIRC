package io.nayuki.mamirc.common;

import java.io.File;
import java.io.IOException;
import io.nayuki.json.Json;


// Represents the connector configuration data. Immutable structure.
public final class ConnectorConfiguration {
	
	/*---- Fields ----*/
	
	// Not null. This is an uninterpreted string, and file existence isn't checked.
	public final File databaseFile;
	
	// Not null, and at least 0 bytes long.
	private final byte[] connectorPassword;
	
	// In the range [0, 65535].
	public final int serverPort;
	
	
	/*---- Constructor ----*/
	
	// Reads the given JSON file and initializes this data structure.
	public ConnectorConfiguration(File file) throws IOException {
		// Parse and do basic check
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-connector-config"))
			throw new IllegalArgumentException("Invalid configuration file");
		
		// Retrieve each field
		databaseFile = new File(Json.getString(data, "database-file"));
		connectorPassword = Utils.toUtf8(Json.getString(data, "processor-password"));
		serverPort = Json.getInt(data, "server-port");
		if ((serverPort & 0xFFFF) != serverPort)
			throw new IllegalStateException("Invalid configuration file");
	}
	
	
	/*---- Getter methods ----*/
	
	public byte[] getConnectorPassword() {
		return connectorPassword.clone();  // Defensive copy
	}
	
}
