package io.nayuki.mamirc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


final class Database implements AutoCloseable {
	
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
		statement.executeUpdate("PRAGMA busy_timeout = 100000");  // In milliseconds
		
		if (create)
			executeInitScript();
	}
	
	
	private void executeInitScript() throws IOException, SQLException {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
				getClass().getClassLoader().getResourceAsStream("resource/database-initialization.sql"),
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
	
	
	public Optional<String> getConfigurationValue(String key) throws SQLException {
		try (PreparedStatement st = connection.prepareStatement("SELECT value FROM configuration WHERE key=?")) {
			st.setString(1, Objects.requireNonNull(key));
			try (ResultSet rs = st.executeQuery()) {
				if (!rs.next())
					return Optional.empty();
				else
					return Optional.of(rs.getString(1));
			}
		}
	}
	
	
	public void setConfigurationValue(String key, String val) throws SQLException {
		try (PreparedStatement st = connection.prepareStatement("INSERT OR REPLACE INTO configuration VALUES (?,?)")) {
			st.setString(1, Objects.requireNonNull(key));
			st.setString(2, Objects.requireNonNull(val));
			st.executeUpdate();
		}
	}
	
	
	public List<Integer> getProfileIds() throws SQLException {
		List<Integer> result = new ArrayList<>();
		try (ResultSet rs = statement.executeQuery("SELECT profile_id FROM irc_network_profiles ORDER BY profile_id ASC")) {
			while (rs.next())
				result.add(rs.getInt(1));
		}
		return result;
	}
	
	
	public boolean getProfileDoConnect(int profileId) throws SQLException {
		try (PreparedStatement st = connection.prepareStatement("SELECT do_connect FROM profile_configuration WHERE profile_id=?")) {
			st.setInt(1, profileId);
			try (ResultSet rs = st.executeQuery()) {
				if (rs.next())
					return rs.getBoolean(1);
			}
		}
		throw new IllegalStateException("Profile missing from database");
	}
	
	
	public List<IrcServer> getProfileServers(int profileId) throws SQLException {
		List<IrcServer> result = new ArrayList<>();
		try (PreparedStatement st = connection.prepareStatement("SELECT hostname, port, tls_mode FROM profile_servers WHERE profile_id=? ORDER BY ordering ASC")) {
			st.setInt(1, profileId);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					IrcServer serv = new IrcServer();
					serv.hostname = rs.getString(1);
					serv.port = rs.getInt(2);
					serv.tlsMode = IrcServer.TlsMode.values()[rs.getInt(3)];
					result.add(serv);
				}
			}
		}
		return result;
	}
	
	
	public String getProfileCharacterEncoding(int profileId) throws SQLException {
		try (PreparedStatement st = connection.prepareStatement("SELECT character_encoding FROM profile_configuration WHERE profile_id=?")) {
			st.setInt(1, profileId);
			try (ResultSet rs = st.executeQuery()) {
				if (rs.next())
					return rs.getString(1);
			}
		}
		throw new IllegalStateException("Profile missing from database");
	}
	
	
	public List<String> getProfileNicknames(int profileId) throws SQLException {
		List<String> result = new ArrayList<>();
		try (PreparedStatement st = connection.prepareStatement("SELECT nickname FROM profile_nicknames WHERE profile_id=? ORDER BY ordering ASC")) {
			st.setInt(1, profileId);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next())
					result.add(rs.getString(1));
			}
		}
		return result;
	}
	
	
	public String getProfileUsername(int profileId) throws SQLException {
		try (PreparedStatement st = connection.prepareStatement("SELECT username FROM profile_configuration WHERE profile_id=?")) {
			st.setInt(1, profileId);
			try (ResultSet rs = st.executeQuery()) {
				if (rs.next())
					return rs.getString(1);
			}
		}
		throw new IllegalStateException("Profile missing from database");
	}
	
	
	public String getProfileRealName(int profileId) throws SQLException {
		try (PreparedStatement st = connection.prepareStatement("SELECT real_name FROM profile_configuration WHERE profile_id=?")) {
			st.setInt(1, profileId);
			try (ResultSet rs = st.executeQuery()) {
				if (rs.next())
					return rs.getString(1);
			}
		}
		throw new IllegalStateException("Profile missing from database");
	}
	
	
	public List<String> getProfileAfterRegistrationCommands(int profileId) throws SQLException {
		List<String> result = new ArrayList<>();
		try (PreparedStatement st = connection.prepareStatement("SELECT command FROM profile_after_registration_commands WHERE profile_id=? ORDER BY ordering ASC")) {
			st.setInt(1, profileId);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next())
					result.add(rs.getString(1));
			}
		}
		return result;
	}
	
	
	public long addConnection(int profileId) throws SQLException {
		statement.executeUpdate("BEGIN IMMEDIATE TRANSACTION");
		boolean ok = false;
		try {
			long result;
			try (ResultSet rs = statement.executeQuery("SELECT ifnull(max(connection_id)+1,0) FROM connections")) {
				result = rs.getLong(1);
			}
			try (PreparedStatement st = connection.prepareStatement("INSERT INTO connections(connection_id, profile_id) VALUES (?,?)")) {
				st.setLong(1, result);
				st.setInt(2, profileId);
				if (st.executeUpdate() != 1)
					throw new SQLException();
			}
			ok = true;
			return result;
		} finally {
			statement.executeUpdate(ok ? "COMMIT TRANSACTION" : "ROLLBACK TRANSACTION");
		}
	}
	
	
	public void beginImmediateTransaction() throws SQLException {
		statement.executeUpdate("BEGIN IMMEDIATE TRANSACTION");
	}
	
	
	public void commitTransaction() throws SQLException {
		statement.executeUpdate("COMMIT TRANSACTION");
	}
	
	
	public void addConnectionEvent(long connectionId, ConnectionEvent event) throws SQLException {
		try (PreparedStatement st = connection.prepareStatement("INSERT INTO connection_events(connection_id, sequence, timestamp_unix_ms, data) "
				+ "VALUES (?,(SELECT ifnull(max(sequence)+1,0) FROM connection_events WHERE connection_id=?),?,?)")) {
			st.setLong(1, connectionId);
			st.setLong(2, connectionId);
			st.setLong(3, event.timestampUnixMs);
			st.setBytes(4, event.toBytes());
			st.executeUpdate();
		}
	}
	
	
	public void addProcessedMessage(int profileId, String displayName, long timestampUnixMs, String data) throws SQLException {
		String canonicalName = ConnectionState.toCanonicalCase(displayName);
		long windowId;
		while (true) {
			try (PreparedStatement st = connection.prepareStatement("SELECT window_id FROM message_windows WHERE profile_id=? and canonical_name=?")) {
				st.setInt(1, profileId);
				st.setString(2, canonicalName);
				try (ResultSet rs = st.executeQuery()) {
					if (rs.next()) {
						windowId = rs.getLong(1);
						break;
					}
				}
			}
			
			try (PreparedStatement st = connection.prepareStatement("INSERT INTO message_windows(window_id, profile_id, display_name, canonical_name) "
					+ "VALUES ((SELECT ifnull(max(window_id)+1,0) FROM message_windows),?,?,?)")) {
				st.setInt(1, profileId);
				st.setString(2, displayName);
				st.setString(3, canonicalName);
				if (st.executeUpdate() != 1)
					throw new SQLException();
			}
		}
		
		try (PreparedStatement st = connection.prepareStatement("INSERT INTO processed_messages(window_id, sequence, timestamp_unix_ms, data, marked_read) "
				+ "VALUES (?,(SELECT ifnull(max(sequence)+1,0) FROM processed_messages WHERE window_id=?),?,?,0)")) {
			st.setLong(1, windowId);
			st.setLong(2, windowId);
			st.setLong(3, timestampUnixMs);
			st.setString(4, data);
			if (st.executeUpdate() != 1)
				throw new SQLException();
		}
	}
	
	
	public Map<String,Object> listProfilesAndMessageWindows() throws SQLException {
		statement.executeUpdate("BEGIN TRANSACTION");
		Map<String,Object> result = new HashMap<>();
		{
			List<Map<String,Object>> profiles = new ArrayList<>();
			try (ResultSet rs = statement.executeQuery("SELECT profile_id, profile_name FROM irc_network_profiles ORDER BY profile_id ASC")) {
				while (rs.next()) {
					Map<String,Object> prof = new HashMap<>();
					prof.put("id", rs.getInt(1));
					prof.put("name", rs.getString(2));
					profiles.add(prof);
				}
			}
			result.put("ircNetworkProfiles", profiles);
		}
		{
			List<Map<String,Object>> windows = new ArrayList<>();
			try (ResultSet rs = statement.executeQuery("SELECT window_id, profile_id, display_name FROM message_windows")) {
				while (rs.next()) {
					Map<String,Object> win = new HashMap<>();
					win.put("id", rs.getLong(1));
					win.put("profileId", rs.getInt(2));
					win.put("name", rs.getString(3));
					windows.add(win);
				}
			}
			result.put("messageWindows", windows);
		}
		statement.executeUpdate("ROLLBACK TRANSACTION");
		return result;
	}
	
	
	public List<Map<String,Object>> getMessages(long windowId, long sequenceStart, long sequenceEnd) throws SQLException {
		List<Map<String,Object>> result = new ArrayList<>();
		try (PreparedStatement st = connection.prepareStatement("SELECT sequence, timestamp_unix_ms, data, marked_read "
				+ "FROM processed_messages 	WHERE window_id=? and ?<=sequence and sequence<?")) {
			st.setLong(1, windowId);
			st.setLong(2, sequenceStart);
			st.setLong(3, sequenceEnd);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					Map<String,Object> msg = new HashMap<>();
					msg.put("sequence", rs.getLong(1));
					msg.put("timestampUnixMs", rs.getLong(2));
					msg.put("data", rs.getString(3));
					msg.put("markedRead", rs.getBoolean(4));
					result.add(msg);
				}
			}
		}
		return result;
	}
	
}
