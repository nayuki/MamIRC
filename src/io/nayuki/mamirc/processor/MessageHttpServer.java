package io.nayuki.mamirc.processor;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
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
		for (String[] entry : STATIC_FILES) {
			String uriPath = entry[1] == null ? "/" + entry[0] : entry[1];
			server.createContext(uriPath, new FileHttpHandler(uriPath, new File("web", entry[0]), entry[2], !entry[2].startsWith("image/")));
		}
		
		// Dynamic actions
		HttpHandler apiHandler = new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				// Read request and parse JSON
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				InputStream in = he.getRequestBody();
				try {
					int len = 0;
					byte[] buf = new byte[1024];
					while (true) {
						int n = in.read(buf);
						if (n == -1)
							break;
						bout.write(buf, 0, n);
						len += n;
						if (len > MAX_REQUEST_BODY_LEN)
							throw new RuntimeException("Request body too long");
					}
				} finally {
					in.close();
				}
				Object reqData = Json.parse(Utils.fromUtf8(bout.toByteArray()));
				
				// Check password field
				if (!checkPassword(Json.getString(reqData, "password"))) {
					writeJsonResponse("Authentication error", he);
					return;
				}
				
				// Handle each API call
				switch (he.getRequestURI().getPath()) {
					case "/get-state.json": {
						writeJsonResponse(master.getState(Json.getInt(reqData, "maxMessagesPerWindow")), he);
						break;
					}
					
					case "/get-updates.json": {
						// Get or wait for new data
						int startId = Json.getInt(reqData, "nextUpdateId");
						synchronized(master) {
							Map<String,Object> data = master.getUpdates(startId);
							if (data != null && Json.getList(data, "updates").size() == 0) {
								try {
									master.wait(60000);
								} catch (InterruptedException e) {}
								data = master.getUpdates(startId);
							}
							writeJsonResponse(data, he);
						}
						break;
					}
					
					case "/do-actions.json": {
						boolean result = true;
						for (Object row : Json.getList(reqData, "payload")) {
							List<Object> tuple = Json.getList(row);
							String profile = Json.getString(tuple, 1);
							String party = Json.getString(tuple, 2);
							
							switch (Json.getString(tuple, 0)) {
								case "send-line": {
									// Tuple index 2 is actually the payload line (e.g. "PRIVMSG #foo :Hello, world!")
									result &= master.sendLine(profile, party);
									break;
								}
								case "mark-read": {
									master.markRead(profile, party, Json.getInt(tuple, 3));
									break;
								}
								case "clear-lines": {
									master.clearLines(profile, party, Json.getInt(tuple, 3));
									break;
								}
								case "open-window": {
									master.openWindow(profile, party);
									break;
								}
								case "close-window": {
									master.closeWindow(profile, party);
									break;
								}
								default:
									throw new AssertionError();
							}
						}
						writeJsonResponse(result, he);
						break;
					}
						
					default:
						throw new AssertionError();
				}
			}
		};
		server.createContext("/get-state.json", apiHandler);
		server.createContext("/get-updates.json", apiHandler);
		server.createContext("/do-actions.json", apiHandler);
		
		// Start the server
		executor = Executors.newFixedThreadPool(4);
		server.setExecutor(executor);
		server.start();
	}
	
	
	private static final String[][] STATIC_FILES = {
		{"mamirc-web-ui.html", "/" , "application/xhtml+xml" },
		{"mamirc.css"        , null, "text/css"              },
		{"mamirc.js"         , null, "application/javascript"},
		{"tomoe-mami.png"    , null, "image/png"             },
		{"tomoe-mami-2x.png" , null, "image/png"             },
	};
	
	
	private static final int MAX_REQUEST_BODY_LEN = 10000;
	
	
	
	/*---- Methods ----*/
	
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
	
	
	private static void writeJsonResponse(Object data, HttpExchange he) throws IOException {
		writeResponse(Utils.toUtf8(Json.serialize(data)), "application/json; charset=UTF-8", true, he);
	}
	
	
	private static void writeResponse(byte[] data, String type, boolean compressible, HttpExchange he) throws IOException {
		Headers head = he.getResponseHeaders();
		head.set("Content-Type", type);
		
		if (compressible && data.length > 3000) {  // Try to use compression
			String accepted = he.getRequestHeaders().getFirst("Accept-Encoding");
			if (accepted != null) {
				for (String acc : accepted.split("\\s*,\\s*")) {
					if (acc.equalsIgnoreCase("deflate")) {
						ByteArrayOutputStream bout = new ByteArrayOutputStream();
						DeflaterOutputStream dout = new DeflaterOutputStream(bout);
						dout.write(data);
						dout.close();
						data = bout.toByteArray();
						head.set("Content-Encoding", "deflate");
						break;
					}
				}
			}
		}
		
		he.sendResponseHeaders(200, data.length);
		he.getResponseBody().write(data);
		he.close();
	}
	
	
	
	/*---- Helper class ----*/
	
	private static final class FileHttpHandler implements HttpHandler {
		
		private final String uriPath;
		private final File file;
		private final String mediaType;
		private final boolean isCompressible;
		
		
		public FileHttpHandler(String uriPath, File file, String mediaType, boolean isCompressible) {
			if (uriPath == null || file == null || mediaType == null)
				throw new NullPointerException();
			this.uriPath = uriPath;
			this.file = file;
			this.mediaType = mediaType;
			this.isCompressible = isCompressible;
		}
		
		
		public void handle(HttpExchange he) throws IOException {
			try {
				if (!he.getRequestURI().getPath().equals(uriPath)) {  // If this handler was called on a subpath
					he.sendResponseHeaders(404, 0);
					return;
				}
				
				String etag = "\"" + file.lastModified() + "\"";
				String ifnonematch = he.getRequestHeaders().getFirst("If-None-Match");
				Headers respHead = he.getResponseHeaders();
				respHead.set("Cache-Control", "public, max-age=2500000, no-cache");
				respHead.set("ETag", etag);
				if (ifnonematch != null && ifnonematch.equals(etag)) {
					he.sendResponseHeaders(304, -1);  // Not Modified
					return;
				}
				
				byte[] b = new byte[(int)file.length()];
				DataInputStream in = new DataInputStream(new FileInputStream(file));
				try {
					in.readFully(b);
				} finally {
					in.close();
				}
				writeResponse(b, mediaType, isCompressible, he);
			} finally {
				he.close();
			}
		}
		
	}
	
}
