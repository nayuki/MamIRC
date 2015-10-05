package io.nayuki.mamirc.processor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.nayuki.json.Json;
import io.nayuki.mamirc.common.Utils;


final class MessageHttpServer {
	
	/*---- Fields ----*/
	
	private final HttpServer server;
	private final ExecutorService executor;
	private final String password;
	
	
	/*---- Constructor ----*/
	
	public MessageHttpServer(final MamircProcessor master, int port, String password) throws IOException {
		this.password = password;
		
		server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		
		// Static files
		server.createContext("/", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				try {
					Headers head = he.getResponseHeaders();
					String reqPath = he.getRequestURI().getPath();
					for (String[] entry : STATIC_FILES) {
						if (entry[0].equals(reqPath)) {
							head.set("Content-Type", entry[2]);
							he.sendResponseHeaders(200, 0);
							InputStream in = new FileInputStream(new File("web", entry[1]));
							try {
								copyStream(in, he.getResponseBody());
							} finally {
								in.close();
							}
							return;
						}
					}
					he.sendResponseHeaders(404, 0);
				} finally {
					he.close();
				}
			}
		});
		
		server.createContext("/get-state.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				// Read request
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				copyStream(he.getRequestBody(), bout);
				Object reqData = Json.parse(Utils.fromUtf8(bout.toByteArray()));
				
				Object respData;
				if (checkPassword(Json.getString(reqData, "password")))
					respData = master.getState();
				else
					respData = "Authentication error";
				
				byte[] b = Utils.toUtf8(Json.serialize(respData));
				Headers head = he.getResponseHeaders();
				head.set("Content-Type", "application/json; charset=UTF-8");
				head.set("Cache-Control", "no-cache");
				he.sendResponseHeaders(200, b.length);
				he.getResponseBody().write(b);
				he.close();
			}
		});
		
		server.createContext("/get-updates.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				// Read request
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				copyStream(he.getRequestBody(), bout);
				Object reqData = Json.parse(Utils.fromUtf8(bout.toByteArray()));
				
				Object respData;
				if (checkPassword(Json.getString(reqData, "password"))) {
					// Get or wait for new data
					int startId = Json.getInt(reqData, "nextUpdateId");
					synchronized(master) {
						respData = master.getUpdates(startId);
						if (respData != null && Json.getList(respData, "updates").size() == 0) {
							try {
								master.wait(60000);
							} catch (InterruptedException e) {}
							respData = master.getUpdates(startId);
						}
					}
				} else
					respData = "Authentication error";
				
				// Write response
				Headers head = he.getResponseHeaders();
				head.set("Content-Type", "application/json; charset=UTF-8");
				head.set("Cache-Control", "no-cache");
				byte[] b = Utils.toUtf8(Json.serialize(respData));
				he.sendResponseHeaders(200, b.length);
				he.getResponseBody().write(b);
				he.close();
			}
		});
		
		server.createContext("/send-message.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				copyStream(he.getRequestBody(), bout);
				Object reqData = Json.parse(Utils.fromUtf8(bout.toByteArray()));
				
				Object respData;
				if (checkPassword(Json.getString(reqData, "password"))) {
					String profile = Json.getString(reqData, "payload", 0);
					String target = Json.getString(reqData, "payload", 1);
					String line = Json.getString(reqData, "payload", 2);
					respData = master.sendMessage(profile, target, line);
				} else
					respData = "Authentication error";
				
				Headers head = he.getResponseHeaders();
				head.set("Content-Type", "application/json; charset=UTF-8");
				head.set("Cache-Control", "no-cache");
				byte[] b = Utils.toUtf8(Json.serialize(respData));
				he.sendResponseHeaders(200, b.length);
				he.getResponseBody().write(b);
				he.close();
			}
		});
		
		executor = Executors.newFixedThreadPool(4);
		server.setExecutor(executor);
		server.start();
	}
	
	
	private static final String[][] STATIC_FILES = {
		{"/", "mamirc-web-ui.html", "application/xhtml+xml"},
		{"/mamirc.css", "mamirc.css", "text/css"},
		{"/mamirc.js", "mamirc.js", "application/javascript"},
	};
	
	
	/*---- Methods ----*/
	
	private static void copyStream(InputStream in, OutputStream out) throws IOException {
		byte[] buf = new byte[1024];
		while (true) {
			int n = in.read(buf);
			if (n == -1)
				break;
			out.write(buf, 0, n);
		}
	}
	
	
	public void terminate() {
		server.stop(0);
		executor.shutdown();
	}
	
	
	private boolean checkPassword(String s) {
		if (s.length() != password.length())
			return false;
		int diff = 0;
		for (int i = 0; i < s.length(); i++)
			diff ^= s.charAt(i) ^ password.charAt(i);
		return diff == 0;
	}
	
}
