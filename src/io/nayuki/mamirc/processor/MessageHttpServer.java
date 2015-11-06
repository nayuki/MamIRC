package io.nayuki.mamirc.processor;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
	
	private String csrfToken;
	
	
	
	/*---- Constructor ----*/
	
	public MessageHttpServer(final MamircProcessor master, int port, String pswd) throws IOException {
		this.password = pswd;
		
		Random rand = new SecureRandom();
		csrfToken = "";
		for (int i = 0; i < 16; i++)
			csrfToken += (char)('a' + rand.nextInt(26));
		
		server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		
		// Static files
		for (String[] entry : STATIC_FILES) {
			String uriPath = entry[1] == null ? "/" + entry[0] : entry[1];
			server.createContext(uriPath, new FileHttpHandler(uriPath, new File("web", entry[0]), entry[2], !entry[2].startsWith("image/")));
		}
		
		// Main/login page
		server.createContext("/", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				try {
					if (!he.getRequestURI().getPath().equals("/"))
						throw new IllegalArgumentException();
					
					if (he.getRequestMethod().equals("POST")) {
						String type = he.getRequestHeaders().getFirst("Content-Type");
						if (type == null || !type.equals("application/x-www-form-urlencoded"))
							throw new IllegalArgumentException();
						
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
						
						Map<String,String> formdata = parseForm(Utils.fromUtf8(bout.toByteArray()));
						Headers respHead = he.getResponseHeaders();
						respHead.add("Set-Cookie", "password=" + (formdata.containsKey("password") ? formdata.get("password").replaceAll("[^A-Za-z0-9]", "") : "") + "; Max-Age=2500000");
						respHead.add("Set-Cookie", "optimize-mobile=" + (formdata.containsKey("optimize-mobile") && formdata.get("optimize-mobile").equals("on")) + "; Max-Age=2500000");
						respHead.add("Location", "/");
						he.sendResponseHeaders(303, -1);
					}
					
					if (!he.getRequestMethod().equals("GET"))
						throw new IllegalArgumentException();
					
					Map<String,String> cookies = parseCookies(he.getRequestHeaders().getFirst("Cookie"));
					if (!(cookies.containsKey("password") && equalsTimingSafe(cookies.get("password"), password))) {
						File file = new File("web", "login.html");
						String s;
						DataInputStream in = new DataInputStream(new FileInputStream(file));
						try {
							byte[] b = new byte[(int)file.length()];
							in.readFully(b);
							s = Utils.fromUtf8(b);
						} finally {
							in.close();
						}
						s = s.replace("#status#", cookies.containsKey("password") && !equalsTimingSafe(cookies.get("password"), password) ? "Incorrect password" : "");
						s = s.replace("#optimize-mobile#", cookies.containsKey("optimize-mobile") && cookies.get("optimize-mobile").equals("true") ? "checked=\"checked\"" : "");
						he.getResponseHeaders().add("Cache-Control", "no-store");
						writeResponse(Utils.toUtf8(s), "application/xhtml+xml", true, he);
					} else {
						File file = new File("web", "mamirc.html");
						byte[] b = new byte[(int)file.length()];
						DataInputStream in = new DataInputStream(new FileInputStream(file));
						try {
							in.readFully(b);
						} finally {
							in.close();
						}
						he.getResponseHeaders().add("Cache-Control", "no-store");
						writeResponse(b, "application/xhtml+xml", true, he);
					}
				} catch (IllegalArgumentException e) {
					he.sendResponseHeaders(404, -1);
				} finally {
					he.close();
				}
			}
		});
		
		// Dynamic actions
		HttpHandler apiHandler = new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				// Check password cookie
				Map<String,String> cookies = parseCookies(he.getRequestHeaders().getFirst("Cookie"));
				if (!(cookies.containsKey("password") && equalsTimingSafe(cookies.get("password"), password))) {
					writeJsonResponse("Authentication error", he);
					return;
				}
				
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
				
				// Handle each API call
				switch (he.getRequestURI().getPath()) {
					case "/get-state.json": {
						Map<String,Object> data = master.getState(Json.getInt(reqData, "maxMessagesPerWindow"));
						data.put("csrfToken", csrfToken);
						writeJsonResponse(data, he);
						break;
					}
					
					case "/get-time.json": {
						writeJsonResponse(System.currentTimeMillis(), he);
						break;
					}
					
					case "/get-updates.json": {
						// Get or wait for new data
						int startId = Json.getInt(reqData, "nextUpdateId");
						int maxWait = Json.getInt(reqData, "maxWait");
						maxWait = Math.max(Math.min(maxWait, 300000), 1);
						synchronized(master) {
							Map<String,Object> data = master.getUpdates(startId);
							if (data != null && Json.getList(data, "updates").size() == 0) {
								try {
									master.wait(maxWait);
								} catch (InterruptedException e) {}
								data = master.getUpdates(startId);
							}
							writeJsonResponse(data, he);
						}
						break;
					}
					
					case "/do-actions.json": {
						String result;
						if (!equalsTimingSafe(Json.getString(reqData, "csrfToken"), csrfToken)) {
							result = "CSRF check failed";
						} else {
							result = "OK";
							for (Object row : Json.getList(reqData, "payload")) {
								List<Object> tuple = Json.getList(row);
								String profile = Json.getString(tuple, 1);
								String party = Json.getString(tuple, 2);
								
								switch (Json.getString(tuple, 0)) {
									case "send-line": {
										// Tuple index 2 is actually the payload line (e.g. "PRIVMSG #foo :Hello, world!")
										if (!master.sendLine(profile, party))
											result = "Profile not found";
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
		server.createContext("/get-time.json", apiHandler);
		server.createContext("/get-updates.json", apiHandler);
		server.createContext("/do-actions.json", apiHandler);
		
		// Start the server
		executor = Executors.newFixedThreadPool(10);
		server.setExecutor(executor);
		server.start();
	}
	
	
	private static final String[][] STATIC_FILES = {
		{"login.css"         , null, "text/css"              },
		{"mamirc.css"        , null, "text/css"              },
		{"mamirc.js"         , null, "application/javascript"},
		{"tomoe-mami.png"    , null, "image/png"             },
		{"tomoe-mami-2x.png" , null, "image/png"             },
		{"tomoe-mami-icon.png", null, "image/png"             },
		{"tomoe-mami-icon-text.png", null, "image/png"             },
	};
	
	
	private static final int MAX_REQUEST_BODY_LEN = 10000;
	
	
	
	/*---- Methods ----*/
	
	public void terminate() {
		server.stop(0);
		executor.shutdown();
	}
	
	
	// Performs a constant-time equality comparison if both strings are the same length.
	private static boolean equalsTimingSafe(String s, String t) {
		if (s.length() != t.length())
			return false;
		int diff = 0;
		for (int i = 0; i < s.length(); i++)
			diff ^= s.charAt(i) ^ t.charAt(i);
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
	
	
	// Naive parser, not fully standards-compliant.
	private static Map<String,String> parseCookies(String raw) {
		Map<String,String> result = new HashMap<>();
		if (raw != null) {
			String[] parts = raw.split("\\s*;\\s*");
			for (String item : parts) {
				String[] subparts = item.split("\\s*=\\s*", 2);
				if (subparts.length == 2)
					result.put(subparts[0], subparts[1]);
			}
		}
		return result;
	}
	
	
	// Naive parser, not fully standards-compliant.
	private static Map<String,String> parseForm(String raw) {
		Map<String,String> result = new HashMap<>();
		String[] parts = raw.split("&");
		for (String item : parts) {
			String[] subparts = item.split("=", 2);
			if (subparts.length == 2)
				result.put(subparts[0], subparts[1]);
		}
		return result;
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
