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
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import io.nayuki.mamirc.IrcNetworkProfile.Server;


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
	
	
	public Collection<IrcNetworkProfile> getIrcNetworkProfiles() throws SQLException {
		Collection<IrcNetworkProfile> result = new ArrayList<>();
		try (ResultSet rs0 = statement.executeQuery("SELECT irc_network_profiles.profile_id, profile_name, do_connect, username, real_name, character_encoding FROM irc_network_profiles JOIN profile_configuration USING (profile_id) ORDER BY profile_id ASC");
				PreparedStatement st1 = connection.prepareStatement("SELECT hostname, port, tls_mode FROM profile_servers WHERE profile_id=? ORDER BY ordering ASC");
				PreparedStatement st2 = connection.prepareStatement("SELECT nickname FROM profile_nicknames WHERE profile_id=? ORDER BY ordering ASC");
				PreparedStatement st3 = connection.prepareStatement("SELECT command FROM profile_after_registration_commands WHERE profile_id=? ORDER BY ordering ASC")) {
			
			while (rs0.next()) {
				IrcNetworkProfile prof = new IrcNetworkProfile();
				prof.id = rs0.getInt(1);
				prof.name = rs0.getString(2);
				prof.doConnect = rs0.getBoolean(3);
				prof.username = rs0.getString(4);
				prof.realName = rs0.getString(5);
				prof.characterEncoding = rs0.getString(6);
				
				st1.setInt(1, prof.id);
				try (ResultSet rs1 = st1.executeQuery()) {
					while (rs1.next()) {
						Server serv = new IrcNetworkProfile.Server();
						serv.hostname = rs1.getString(1);
						serv.port = rs1.getInt(2);
						serv.tlsMode = IrcNetworkProfile.Server.TlsMode.values()[rs1.getInt(3)];
						prof.servers.add(serv);
					}
				}
				
				st2.setInt(1, prof.id);
				try (ResultSet rs2 = st2.executeQuery()) {
					while (rs2.next())
						prof.nicknames.add(rs2.getString(1));
				}
				
				st3.setInt(1, prof.id);
				try (ResultSet rs3 = st3.executeQuery()) {
					while (rs3.next())
						prof.afterRegistrationCommands.add(rs3.getString(1));
				}
				
				result.add(prof);
			}
		}
		return result;
	}
	
	
	public void setIrcNetworkProfiles(Collection<IrcNetworkProfile> profiles) throws SQLException {
		statement.executeUpdate("BEGIN IMMEDIATE TRANSACTION");
		boolean ok = false;
		try (PreparedStatement st0 = connection.prepareStatement("UPDATE irc_network_profiles SET profile_name=? WHERE profile_id=?");
				PreparedStatement st1 = connection.prepareStatement("INSERT OR IGNORE INTO irc_network_profiles(profile_id, profile_name) VALUES (?,?)");
				PreparedStatement st2 = connection.prepareStatement("INSERT INTO profile_configuration(profile_id, do_connect, username, real_name, character_encoding) VALUES (?,?,?,?,?)");
				PreparedStatement st3 = connection.prepareStatement("INSERT INTO profile_servers(profile_id, ordering, hostname, port, tls_mode) VALUES (?,?,?,?,?)");
				PreparedStatement st4 = connection.prepareStatement("INSERT INTO profile_nicknames(profile_id, ordering, nickname) VALUES (?,?,?)");
				PreparedStatement st5 = connection.prepareStatement("INSERT INTO profile_after_registration_commands(profile_id, ordering, command) VALUES (?,?,?)")) {
			
			statement.executeUpdate("DELETE FROM profile_configuration");
			statement.executeUpdate("DELETE FROM profile_servers");
			statement.executeUpdate("DELETE FROM profile_nicknames");
			statement.executeUpdate("DELETE FROM profile_after_registration_commands");
			
			for (IrcNetworkProfile prof : profiles) {
				st0.setString(1, prof.name);
				st0.setInt(2, prof.id);
				st0.executeUpdate();
				
				st1.setInt(1, prof.id);
				st1.setString(2, prof.name);
				st1.executeUpdate();
				
				st2.setInt(1, prof.id);
				st2.setBoolean(2, prof.doConnect);
				st2.setString(3, prof.username);
				st2.setString(4, prof.realName);
				st2.setString(5, prof.characterEncoding);
				st2.executeUpdate();
				
				st3.setInt(1, prof.id);
				for (int i = 0; i < prof.servers.size(); i++) {
					st3.setInt(2, i);
					Server serv = prof.servers.get(i);
					st3.setString(3, serv.hostname);
					st3.setInt(4, serv.port);
					st3.setInt(5, serv.tlsMode.ordinal());
					st3.executeUpdate();
				}
				
				st4.setInt(1, prof.id);
				for (int i = 0; i < prof.nicknames.size(); i++) {
					st4.setInt(2, i);
					st4.setString(3, prof.nicknames.get(i));
					st4.executeUpdate();
				}
				
				st5.setInt(1, prof.id);
				for (int i = 0; i < prof.afterRegistrationCommands.size(); i++) {
					st5.setInt(2, i);
					st5.setString(3, prof.afterRegistrationCommands.get(i));
					st5.executeUpdate();
				}
			}
			
			ok = true;
		} finally {
			statement.executeUpdate(ok ? "COMMIT TRANSACTION" : "ROLLBACK TRANSACTION");
		}
	}
	
}
