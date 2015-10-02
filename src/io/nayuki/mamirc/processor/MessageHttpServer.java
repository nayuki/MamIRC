package io.nayuki.mamirc.processor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.nayuki.json.Json;
import io.nayuki.mamirc.common.Utils;


final class MessageHttpServer {
	
	/*---- Fields ----*/
	
	public final HttpServer server;
	private final MamircProcessor master;
	
	
	/*---- Constructor ----*/
	
	public MessageHttpServer(MamircProcessor master, int port) throws IOException {
		this.master = master;
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
				byte[] b = Utils.toUtf8(Json.serialize(master.getState()));
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
				int startId = Json.getInt(Json.parse(Utils.fromUtf8(bout.toByteArray())));
				
				// Get or wait for new data
				Map<String,Object> data;
				synchronized(master) {
					data = master.getUpdates(startId);
					if (data != null && Json.getList(data, "updates").size() == 0) {
						try {
							master.wait(60000);
						} catch (InterruptedException e) {}
						data = master.getUpdates(startId);
					}
				}
				
				// Write response
				Headers head = he.getResponseHeaders();
				head.set("Content-Type", "application/json; charset=UTF-8");
				head.set("Cache-Control", "no-cache");
				byte[] b = Utils.toUtf8(Json.serialize(data));
				he.sendResponseHeaders(200, b.length);
				he.getResponseBody().write(b);
				he.close();
			}
		});
		
		server.createContext("/send-message.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				handleSendMessage(he);
			}
		});
		
		server.setExecutor(Executors.newFixedThreadPool(4));
	}
	
	
	private static final String[][] STATIC_FILES = {
		{"/", "mamirc-web-ui.html", "application/xhtml+xml"},
		{"/mamirc.css", "mamirc.css", "text/css"},
		{"/mamirc.js", "mamirc.js", "application/javascript"},
	};
	
	
	/*---- Methods ----*/
	
	private void handleSendMessage(HttpExchange he) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		InputStream in = he.getRequestBody();
		byte[] buf = new byte[1024];
		while (true) {
			int n = in.read(buf);
			if (n == -1)
				break;
			bout.write(buf, 0, n);
		}
		Object data = Json.parse(Utils.fromUtf8(bout.toByteArray()));
		String profile = Json.getString(data, 0);
		String target = Json.getString(data, 1);
		String line = Json.getString(data, 2);
		boolean status = master.sendMessage(profile, target, line);
		
		Headers head = he.getResponseHeaders();
		head.set("Content-Type", "application/json; charset=UTF-8");
		head.set("Cache-Control", "no-cache");
		byte[] b = Utils.toUtf8(Boolean.toString(status));
		he.sendResponseHeaders(200, b.length);
		he.getResponseBody().write(b);
		he.close();
	}
	
	
	private static void copyStream(InputStream in, OutputStream out) throws IOException {
		byte[] buf = new byte[1024];
		while (true) {
			int n = in.read(buf);
			if (n == -1)
				break;
			out.write(buf, 0, n);
		}
	}
	
}
