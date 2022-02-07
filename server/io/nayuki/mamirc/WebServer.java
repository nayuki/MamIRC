package io.nayuki.mamirc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.nayuki.json.Json;


final class WebServer {
	
	private Core core;
	private HttpServer server;
	private ExecutorService executor;
	
	
	public WebServer(Core core) throws IOException, SQLException {
		this.core = core;
		
		int port;
		try (Database db = new Database(core.getDatabaseFile())) {
			Optional<String> temp = db.getConfigurationValue("HTTP server port");
			if (temp.isEmpty())
				throw new IllegalStateException("Port missing from configuration table");
			port = Integer.parseInt(temp.get());
		}
		server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		
		server.createContext("/message-windows.json", this::getMessageWindows);
		
		executor = Executors.newFixedThreadPool(30);
		server.setExecutor(executor);
		server.start();
	}
	
	
	private void getMessageWindows(HttpExchange he) throws IOException {
		try {
			if (!he.getRequestMethod().equals("GET")) {
				he.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, ZERO_LENGTH);
				return;
			}
			
			Object result;
			try (Database db = new Database(core.getDatabaseFile())) {
				result = db.listProfilesAndMessageWindows();
			}
			he.getResponseHeaders().add("Content-Type", "application/json");
			he.getResponseHeaders().add("Cache-Control", "no-store");
			he.sendResponseHeaders(HTTP_OK, UNKNOWN_LENGTH);
			String json = Json.serialize(result);
			he.getResponseBody().write(json.getBytes(StandardCharsets.UTF_8));
			
		} catch (SQLException e) {
			e.printStackTrace();
			he.sendResponseHeaders(HTTP_INTERNAL_SERVER_ERROR, ZERO_LENGTH);
		} finally {
			he.close();
		}
	}
	
	
	public void terminate() {
		server.stop(0);
		executor.shutdown();
	}
	
	
	
	private static final int HTTP_OK = 200;
	private static final int HTTP_METHOD_NOT_ALLOWED = 405;
	private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
	
	private static final int ZERO_LENGTH = -1;
	private static final int UNKNOWN_LENGTH = 0;
	
}
