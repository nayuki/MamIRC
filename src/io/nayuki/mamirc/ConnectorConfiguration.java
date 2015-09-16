package io.nayuki.mamirc;

import java.io.File;
import java.io.IOException;
import io.nayuki.json.Json;


// Immutable structure. Data is loaded from a JSON file.
final class ConnectorConfiguration {
	
	/*---- Fields ----*/
	
	private final Object data;
	
	
	/*---- Constructor ----*/
	
	public ConnectorConfiguration(File file) throws IOException {
		// Parse and do basic check
		data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-connector-config"))
			throw new IllegalArgumentException("Invalid configuration file");
	}
	
	
	/*---- Getter methods ----*/
	
	public File getDatabaseFile() {
		return new File(Json.getString(data, "database-file"));
	}
	
	
	public byte[] getConnectorPassword() {
		return Json.getString(data, "processor-password").getBytes(OutputWriterThread.UTF8_CHARSET);
	}
	
	
	public int getServerPort() {
		int result = Json.getInt(data, "server-port");
		if ((result & 0xFFFF) != result)
			throw new IllegalStateException("Invalid configuration file");
		return result;
	}
	
}
