package io.nayuki.mamirc.common;

import java.io.File;
import java.io.IOException;
import io.nayuki.json.Json;


/* 
 * Represents configuration data for the connector itself, and for
 * the processor to access the connector. Immutable structure.
 */
public final class ConnectorConfiguration {
	
	/*---- Fields ----*/
	
	// Not null. This is an uninterpreted string, and file existence is not checked.
	public final File databaseFile;
	
	// Not null, and at least 0 bytes long.
	private final byte[] connectorPassword;
	
	// In the range [0, 65535].
	public final int serverPort;
	
	
	/*---- Constructor ----*/
	
	// Constructs an object by reading the file at the given path.
	public ConnectorConfiguration(File file) throws IOException {
		// Parse JSON data and check signature
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-connector-config"))
			throw new IllegalArgumentException("Invalid configuration file type");
		
		// Retrieve each field
		databaseFile = new File(Json.getString(data, "database-file"));
		connectorPassword = Utils.toUtf8(Json.getString(data, "processor-password"));
		serverPort = Json.getInt(data, "server-port");
		if ((serverPort & 0xFFFF) != serverPort)
			throw new IllegalStateException("Invalid TCP port number");
	}
	
	
	/*---- Getter methods ----*/
	
	public byte[] getConnectorPassword() {
		return connectorPassword.clone();  // Defensive copy
	}
	
}
