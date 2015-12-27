package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import io.nayuki.json.Json;


/* 
 * Represents configuration data for the processor. Immutable structure.
 */
final class ProcessorConfiguration {
	
	/*---- Fields ----*/
	
	public final int webServerPort;  // In the range [0, 65535].
	public final String webUiPassword;  // Not null
	
	
	/*---- Constructor ----*/
	
	// Constructs an object by reading the file at the given path.
	public ProcessorConfiguration(File file) throws IOException {
		// Parse JSON data and check signature
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-processor-config"))
			throw new IllegalArgumentException("Invalid configuration file type");
		
		// Convert to internal data format
		webServerPort = Json.getInt(data, "web-server-port");
		if ((webServerPort & 0xFFFF) != webServerPort)
			throw new IllegalStateException("Invalid TCP port number");
		webUiPassword = Json.getString(data, "web-ui-password");
	}
	
}
