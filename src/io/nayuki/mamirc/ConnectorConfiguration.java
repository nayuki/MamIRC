package io.nayuki.mamirc;

import java.io.File;
import java.io.IOException;
import io.nayuki.json.Json;


// Immutable structure. Data is loaded from a JSON file.
final class ConnectorConfiguration {
	
	/*---- Fields ----*/
	
	public final File databaseFile;
	private final byte[] connectorPassword;
	public final int serverPort;
	
	
	/*---- Constructor ----*/
	
	public ConnectorConfiguration(File file) throws IOException {
		// Parse and do basic check
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-connector-config"))
			throw new IllegalArgumentException("Invalid configuration file");
		
		// Retrieve each field
		databaseFile = new File(Json.getString(data, "database-file"));
		connectorPassword = Json.getString(data, "processor-password").getBytes(OutputWriterThread.UTF8_CHARSET);
		serverPort = Json.getInt(data, "server-port");
		if ((serverPort & 0xFFFF) != serverPort)
			throw new IllegalStateException("Invalid configuration file");
	}
	
	
	/*---- Getter methods ----*/
	
	public byte[] getConnectorPassword() {
		return connectorPassword.clone();
	}
	
}
