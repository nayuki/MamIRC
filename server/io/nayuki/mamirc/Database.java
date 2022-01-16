package io.nayuki.mamirc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;


public final class Database implements AutoCloseable {
	
	private Connection connection;
	private Statement statement;
	
	
	public Database(File file) throws IOException, SQLException {
		boolean create;
		if (file.isDirectory())
			throw new IOException("Database path cannot be directory");
		else if (file.isFile() && file.length() > 0)
			create = false;
		else if (!file.exists() || file.length() == 0)
			create = true;
		else
			throw new IOException("Unknown file type: " + file);
		
		connection = DriverManager.getConnection("jdbc:sqlite:" + file);
		statement = connection.createStatement();
		statement.executeUpdate("PRAGMA foreign_keys = true");
		
		if (create)
			executeInitScript();
	}
	
	
	private void executeInitScript() throws IOException, SQLException {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
				getClass().getClassLoader().getResourceAsStream("resource/mamirc-database-init.sql"),
				StandardCharsets.UTF_8))) {
			
			StringBuilder sb = new StringBuilder();
			while (true) {
				String line = in.readLine();
				if (line == null || line.equals("")) {
					
					statement.execute(sb.toString());
					
					if (line == null)
						break;
					sb.setLength(0);
				} else
					sb.append(line).append("\n");
			}
		}
	}
	
	
	public void close() throws IOException {
		try {
			connection.close();
		} catch (SQLException e) {
			throw new IOException(e);
		}
	}
	
}
