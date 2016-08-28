/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.almworks.sqlite4java.SQLiteException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.nayuki.json.Json;
import io.nayuki.mamirc.common.Utils;


/* 
 * Defines handlers for HTTP URLs, such as serving static files, data retrieval, and action performing.
 */
final class WebServer {
	
	/*---- Fields ----*/
	
	// This maps a URL path to a local file and a media type, e.g.
	// "/foo.png" -> (File("/bin/mamirc/web/foo.png"), "image/png").
	// The data structure has no provisions for thread safety, so it must be set once
	// at the beginning before the server is started, and never modified thereafter.
	// 
	// We use this approach of explicitly defining known files instead of taking the raw URL
	// and using it to query the file system, because some paths and metacharacters may be unsafe.
	// For example, querying the existence of a path on Windows may suffer from aliasing
	// due to case-insensitivity and legacy 8.3 DOS names.
	private final Map<String,Object[]> authorizedStaticFiles;
	
	
	
	/*---- Constructors ----*/
	
	// Constructs and starts a web server with the given arguments.
	public WebServer(int port, final MamircProcessor master, final MessageManager msgMgr) throws IOException {
		// Check arguments
		Utils.checkPortNumber(port);
		if (master == null || msgMgr == null)
			throw new NullPointerException();
		
		// Initialize set of known static files from the "web" subdirectory
		authorizedStaticFiles = scanStaticFiles(new File("web"));
		
		// Create (but not start) the server. By default we only listen to connections from this machine itself,
		// and you are expected put an SSL proxy server in front of MamIRC. If you want to expose
		// the unencrypted HTTP server, then change this line to listen to "0.0.0.0" at your own risk.
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		
		// Mainly handles all static files, plus miscellaneous
		server.createContext("/", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				Object[] info = authorizedStaticFiles.get(he.getRequestURI().getPath());
				if (info != null) {
					he.getResponseHeaders().set("Content-Type", (String)info[1]);
					byte[] data = readFile((File)info[0]);
					he.sendResponseHeaders(200, data.length > 0 ? data.length : -1);
					he.getResponseBody().write(data);
				} else
					he.sendResponseHeaders(404, -1);
				he.close();
			}
		});
		
		server.createContext("/get-window-list.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				try {
					Object respData = msgMgr.listAllWindowsAsJson();
					writeJsonResponse(respData, he);
				} catch (SQLiteException e) {
					he.sendResponseHeaders(500, -1);
					he.close();
				}
			}
		});
		
		server.createContext("/get-window-messages.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				try {
					ByteArrayOutputStream bout = new ByteArrayOutputStream();
					byte[] buf = new byte[1024];
					InputStream hin = he.getRequestBody();
					while (true) {
						int n = hin.read(buf);
						if (n == -1)
							break;
						bout.write(buf, 0, n);
					}
					Object reqData = Json.parse(Utils.fromUtf8(bout.toByteArray()));
					
					Object respData = msgMgr.getWindowMessagesAsJson(
						Json.getString(reqData, "profile"),
						Json.getString(reqData, "party"),
						Json.getInt(reqData, "start"),
						Json.getInt(reqData, "end"));
					writeJsonResponse(respData, he);
				} catch (SQLiteException e) {
					he.sendResponseHeaders(500, -1);
					he.close();
				}
			}
		});
		
		server.createContext("/get-network-profiles.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				Object respData = master.getNetworkProfilesAsJson();
				writeJsonResponse(respData, he);
			}
		});
		
		// Define threads and start serving requests
		ExecutorService executor = Executors.newFixedThreadPool(10);
		server.setExecutor(executor);
		server.start();
	}
	
	
	
	/*---- Methods ----*/
	
	// Scans all files in the given directory non-recursively, and
	// returns a new mapping from URL paths to files and media types.
	private static Map<String,Object[]> scanStaticFiles(File rootDir) {
		Map<String,Object[]> result = new HashMap<>();
		for (File item : rootDir.listFiles()) {
			if (!item.isFile())
				continue;  // Skip non-files (directories, special objects, etc.)
			String name = item.getName();
			if (name.indexOf('.') == -1)
				continue;  // Skip files with no extension
			String ext = name.substring(name.lastIndexOf('.') + 1);
			String type = EXTENSION_TO_MEDIA_TYPE.get(ext);
			if (type == null)
				continue;  // Skip extensions that don't have a known media type
			if (name.endsWith(".html"))  // Map HTML files to extensionless URL paths
				name = name.substring(0, name.length() - ".html".length());
			result.put("/" + name, new Object[]{item, type});
		}
		return result;
	}
	
	
	
	/*---- Static members ----*/
	
	// Writes the given JSON-compatible object as an HTTP response in JSON text to the given HTTP response.
	// The HTTP exchange is closed by this function call. Note that data can be null.
	private static void writeJsonResponse(Object data, HttpExchange he) throws IOException {
		if (he == null)
			throw new NullPointerException();
		String str = Json.serialize(data);
		byte[] bytes = Utils.toUtf8(str);
		he.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
		he.sendResponseHeaders(200, bytes.length > 0 ? bytes.length : -1);
		he.getResponseBody().write(bytes);
		he.close();
	}
	
	
	// Maps a lowercase file extension to an Internet media type.
	private static final Map<String,String> EXTENSION_TO_MEDIA_TYPE;
	static {
		String[][] mapping = {
			{"css" , "text/css"},
			{"gif" , "image/gif"},
			{"html", "application/xhtml+xml; charset=UTF-8"},
			{"jpeg", "image/jpeg"},
			{"jpg" , "image/jpeg"},
			{"js"  , "application/javascript"},
			{"png" , "image/png"},
			{"svg" , "image/svg+xml"},
		};
		Map<String,String> map = new HashMap<>();
		for (String[] pair : mapping)
			map.put(pair[0], pair[1]);
		EXTENSION_TO_MEDIA_TYPE = Collections.unmodifiableMap(map);
	}
	
	
	// Reads and returns the full contents of the given file as a byte array.
	private static byte[] readFile(File file) throws IOException {
		if (file == null)
			throw new NullPointerException();
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		Files.copy(file.toPath(), bout);
		return bout.toByteArray();
	}
	
}
