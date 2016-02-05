/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.io.File;
import java.io.IOException;
import io.nayuki.json.Json;


/* 
 * Represents configuration data for the two backend programs,
 * the Connector and the Processor. Immutable structure.
 */
public final class BackendConfiguration {
	
	/*---- Fields ----*/
	
	// Not null. This is an uninterpreted string, and file existence is not checked.
	public final File connectorDatabaseFile;
	
	// In the range [0, 65535].
	public final int connectorServerPort;
	
	// Not null, and at least 0 bytes long.
	private final byte[] connectorPassword;
	
	// In the range [0, 65535].
	public final int webServerPort;
	
	// Not null.
	public final String webUiPassword;
	
	
	/*---- Constructor ----*/
	
	// Constructs an object by reading the file at the given path.
	public BackendConfiguration(File file) throws IOException {
		// Parse JSON data and check signature
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-backend-config"))
			throw new IllegalArgumentException("Invalid configuration file type");
		
		// Retrieve each field
		connectorDatabaseFile = new File(Json.getString(data, "connector-database-file"));
		connectorServerPort = Utils.checkPortNumber(Json.getInt(data, "connector-server-port"));
		connectorPassword = Utils.toUtf8(Json.getString(data, "connector-password"));
		webServerPort = Utils.checkPortNumber(Json.getInt(data, "web-server-port"));
		webUiPassword = Json.getString(data, "web-ui-password");
	}
	
	
	/*---- Getter methods ----*/
	
	public byte[] getConnectorPassword() {
		return connectorPassword.clone();  // Defensive copy
	}
	
}
