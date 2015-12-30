/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * http://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.nayuki.json.Json;
import io.nayuki.mamirc.common.Utils;
import io.nayuki.mamirc.processor.UserConfiguration.IrcNetwork;


final class MessageHttpServer {
	
	/*---- Fields ----*/
	
	private final HttpServer server;
	private final ExecutorService executor;
	private final String password;
	
	private String csrfToken;
	private Set<String> knownStaticFilenames;
	private long staticFilesLastRefresh;
	
	private final File staticFilesDir = new File("web").getAbsoluteFile();
	
	
	
	/*---- Constructor ----*/
	
	public MessageHttpServer(final MamircProcessor master, int port, String pswd) throws IOException {
		this.password = pswd;
		
		Random rand = new SecureRandom();
		csrfToken = "";
		for (int i = 0; i < 16; i++)
			csrfToken += (char)('a' + rand.nextInt(26));
		
		server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		
		// Main/login page
		server.createContext("/", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				try {
					Headers respHead = he.getResponseHeaders();
					String path = he.getRequestURI().getPath().substring(1);
					
					// Static files
					if (knownStaticFilenames == null || System.currentTimeMillis() - staticFilesLastRefresh > 3000) {
						if (knownStaticFilenames == null)
							knownStaticFilenames = new HashSet<>();
						Collections.addAll(knownStaticFilenames, staticFilesDir.list());
						staticFilesLastRefresh = System.currentTimeMillis();
					}
					if (knownStaticFilenames.contains(path)) {
						File file = new File(staticFilesDir, path);
						String etag = "\"" + file.lastModified() + "\"";
						String ifnonematch = he.getRequestHeaders().getFirst("If-None-Match");
						respHead.set("Cache-Control", "public, max-age=2500000, no-cache");
						respHead.set("ETag", etag);
						if (ifnonematch != null && ifnonematch.equals(etag)) {
							he.sendResponseHeaders(304, -1);  // Not Modified
							return;
						}
						String extension = null;
						int dotIndex = path.lastIndexOf('.');
						if (dotIndex != -1)
							extension = path.substring(dotIndex + 1).toLowerCase();
						String mediaType = "application/octet-stream";
						if (EXTENSION_TO_MEDIA_TYPE.containsKey(extension))
							mediaType = EXTENSION_TO_MEDIA_TYPE.get(extension);
						writeResponse(readFile(file), mediaType, !mediaType.startsWith("image/"), he);
						return;
					}
					
					// Root page
					if (!path.equals(""))
						throw new IllegalArgumentException();
					
					if (he.getRequestMethod().equals("POST")) {
						String type = he.getRequestHeaders().getFirst("Content-Type");
						if (type == null || !type.equals("application/x-www-form-urlencoded"))
							throw new IllegalArgumentException();
						byte[] reqBytes = readBounded(he.getRequestBody());
						Map<String,String> formdata = parseForm(Utils.fromUtf8(reqBytes));
						respHead.add("Set-Cookie", "password=" + (formdata.containsKey("password") ? formdata.get("password").replaceAll("[^A-Za-z0-9]", "") : "") + "; Max-Age=2500000");
						respHead.add("Set-Cookie", "optimize-mobile=" + (formdata.containsKey("optimize-mobile") && formdata.get("optimize-mobile").equals("on")) + "; Max-Age=2500000");
						respHead.add("Location", "/");
						he.sendResponseHeaders(303, -1);
						
					} else if (he.getRequestMethod().equals("GET")) {
						Map<String,String> cookies = parseCookies(he.getRequestHeaders().getFirst("Cookie"));
						if (password.length() == 0 && (!cookies.containsKey("password") || cookies.get("password").length() != 0))
							respHead.add("Set-Cookie", "password=; Max-Age=2500000");
						
						if (password.length() > 0 && !(cookies.containsKey("password") && equalsTimingSafe(cookies.get("password"), password))) {
							// Serve login page
							String s = Utils.fromUtf8(readFile(new File("web", "login.html")));
							s = s.replace("#status#", cookies.containsKey("password") && !equalsTimingSafe(cookies.get("password"), password) ? "Incorrect password" : "");
							s = s.replace("#optimize-mobile#", cookies.containsKey("optimize-mobile") && cookies.get("optimize-mobile").equals("true") ? "checked=\"checked\"" : "");
							respHead.add("Cache-Control", "no-store");
							writeResponse(Utils.toUtf8(s), "application/xhtml+xml", true, he);
						} else {  // Serve main page
							respHead.add("Cache-Control", "no-store");
							writeResponse(readFile(new File("web", "mamirc.html")), "application/xhtml+xml", true, he);
						}
					} else
						throw new IllegalArgumentException();
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
				byte[] reqBytes = readBounded(he.getRequestBody());
				Object reqData = Json.parse(Utils.fromUtf8(reqBytes));
				
				// Handle each API call
				switch (he.getRequestURI().getPath()) {
					case "/get-profiles.json": {
						Map<String,Object> data = master.getProfiles();
						writeJsonResponse(data, he);
						break;
					}
					
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
						Map<String,Object> data = master.getUpdates(startId, maxWait);
						writeJsonResponse(data, he);
						break;
					}
					
					case "/do-actions.json": {
						String result;
						if (!equalsTimingSafe(Json.getString(reqData, "csrfToken"), csrfToken)) {
							result = "CSRF check failed";
						} else if (Json.getInt(reqData, "nextUpdateId") > master.getNextUpdateId()) {
							result = "Invalid future update ID";
						} else if (Json.getInt(reqData, "nextUpdateId") < master.getNextUpdateId() - 10) {
							result = "Client fell too far behind updates";
						} else {
							result = "OK";
							for (Object row : Json.getList(reqData, "payload")) {
								List<Object> tuple = Json.getList(row);
								String command = Json.getString(tuple, 0);
								if (command.equals("set-profiles")) {
									master.setProfiles(convertProfiles(Json.getMap(tuple, 1)));
								} else {
									String profile = Json.getString(tuple, 1);
									String party = Json.getString(tuple, 2);
									switch (command) {
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
										case "set-initial-window": {
											master.setInitialWindow(profile, party);
											break;
										}
										default:
											throw new AssertionError();
									}
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
		server.createContext("/get-profiles.json", apiHandler);
		server.createContext("/get-state.json", apiHandler);
		server.createContext("/get-time.json", apiHandler);
		server.createContext("/get-updates.json", apiHandler);
		server.createContext("/do-actions.json", apiHandler);
		
		// Start the server
		executor = Executors.newFixedThreadPool(10);
		server.setExecutor(executor);
		server.start();
	}
	
	
	private static final Map<String,String> EXTENSION_TO_MEDIA_TYPE = new HashMap<>();
	static {
		Map<String,String> m = EXTENSION_TO_MEDIA_TYPE;
		m.put("css" , "text/css");
		m.put("gif" , "image/gif");
		m.put("jpg" , "image/jpeg");
		m.put("jpeg", "image/jpeg");
		m.put("js"  , "application/javascript");
		m.put("png" , "image/png");
	};
	
	
	
	/*---- Methods ----*/
	
	public void terminate() {
		server.stop(0);
		executor.shutdown();
	}
	
	
	private static Map<String,IrcNetwork> convertProfiles(Map<String,Object> inData) {
		Map<String,IrcNetwork> outData = new HashMap<>();
		for (Map.Entry<String,Object> entry : inData.entrySet()) {
			String name = entry.getKey();
			Map<String,Object> inProfile = Json.getMap(inData, name);
			
			List<Object> inServers = Json.getList(inProfile, "servers");
			List<IrcNetwork.Server> outServers = new ArrayList<>();
			for (Object val : inServers) {
				Map<String,Object> inServer = Json.getMap(val);
				IrcNetwork.Server outServer = new IrcNetwork.Server(
					Json.getString(inServer, "hostname"),
					Json.getInt(inServer, "port"),
					Json.getBoolean(inServer, "ssl"));
				outServers.add(outServer);
			}
			
			List<Object> inNicknames = Json.getList(inProfile, "nicknames");
			List<String> outNicknames = new ArrayList<>();
			for (Object val : inNicknames)
				outNicknames.add(Json.getString(val));
			
			List<Object> inChannels = Json.getList(inProfile, "channels");
			Set<String> outChannels = new HashSet<>();
			for (Object val : inChannels)
				outChannels.add(Json.getString(val));
			
			IrcNetwork outProfile = new IrcNetwork(
				name,
				Json.getBoolean(inProfile, "connect"),
				outServers,
				outNicknames,
				Json.getString(inProfile, "username"),
				Json.getString(inProfile, "realname"),
				(String)Json.getObject(inProfile, "nickservPassword"),
				outChannels);
			outData.put(name, outProfile);
		}
		return outData;
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
	
	
	private static byte[] readFile(File file) throws IOException {
		try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
			byte[] b = new byte[(int)file.length()];
			in.readFully(b);
			return b;
		}
	}
	
	
	private static byte[] readBounded(InputStream in) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		int len = 0;
		byte[] buf = new byte[2000];
		while (true) {
			int n = in.read(buf);
			if (n == -1)
				return bout.toByteArray();
			bout.write(buf, 0, n);
			len += n;
			if (len > MAX_REQUEST_BODY_LEN)
				throw new RuntimeException("Input data too long");
		}
	}
	
	
	private static final int MAX_REQUEST_BODY_LEN = 10000;
	
}
