/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import io.nayuki.json.Json;


/* 
 * Represents configuration data for the end user. This structure is mutable and not thread-safe.
 */
final class UserConfiguration {
	
	/*---- Fields ----*/
	
	// Not null. This is an uninterpreted string, and file existence is not checked.
	public final File windowMessagesDatabaseFile;
	
	public final Map<String,NetworkProfile> profiles;  // Not null; keys and values not null.
	
	
	
	/*---- Constructor ----*/
	
	// Constructs a configuration by reading the file at the given path.
	public UserConfiguration(File file) throws IOException {
		// Parse JSON data and check signature
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-user-config"))
			throw new IllegalArgumentException("Invalid configuration file type");
		
		windowMessagesDatabaseFile = new File(Json.getString(data, "window-messages-database-file"));
		
		profiles = new HashMap<>();
		for (Map.Entry<String,Object> entry : Json.getMap(data, "network-profiles").entrySet()) {
			String name = entry.getKey();
			profiles.put(name, new NetworkProfile(name, entry.getValue()));
		}
	}
	
}
