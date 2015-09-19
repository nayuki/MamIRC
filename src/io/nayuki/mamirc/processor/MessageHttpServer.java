package io.nayuki.mamirc.processor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.nayuki.json.Json;
import io.nayuki.mamirc.common.OutputWriterThread;


final class MessageHttpServer {
	
	/*---- Fields ----*/
	
	public final HttpServer server;
	private final MamircProcessor master;
	private final File databaseFile = new File("mamirc-messages.sqlite");
	
	
	/*---- Constructor ----*/
	
	public MessageHttpServer(MamircProcessor master) throws IOException {
		this.master = master;
		server = HttpServer.create(new InetSocketAddress("localhost", 11972), 0);
		
		server.createContext("/", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				Headers head = he.getResponseHeaders();
				OutputStream out = he.getResponseBody();
				switch (he.getRequestURI().getPath()) {
					case "/":
						head.set("Content-Type", "application/xhtml+xml");
						he.sendResponseHeaders(200, 0);
						sendFile(new File("web", "mamirc-web-ui.html"), out);
						break;
					case "/mamirc.css":
						head.set("Content-Type", "text/css");
						he.sendResponseHeaders(200, 0);
						sendFile(new File("web", "mamirc.css"), out);
						break;
					case "/mamirc.js":
						head.set("Content-Type", "application/javascript");
						he.sendResponseHeaders(200, 0);
						sendFile(new File("web", "mamirc.js"), out);
						break;
					default:
						he.sendResponseHeaders(404, 0);
						break;
				}
				he.close();
			}
		});
		
		server.createContext("/get-messages.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				try {
					handleGetMessages(he);
				} catch (SQLiteException e) {
					he.sendResponseHeaders(500, 0);
					he.close();
				}
			}
		});
		
		server.createContext("/send-message.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				handleSendMessage(he);
			}
		});
		
		server.start();
	}
	
	
	/*---- Methods ----*/
	
	private void handleGetMessages(HttpExchange he) throws IOException, SQLiteException {
		SQLiteConnection database = new SQLiteConnection(databaseFile);
		database.open(false);
		SQLiteStatement windowQuery = database.prepare("SELECT id FROM windows WHERE profile=? AND party=?");
		SQLiteStatement messageQuery = database.prepare("SELECT timestamp, line FROM messages WHERE connectionId=? AND windowId=? ORDER BY sequence ASC");
		Map<Object[],List<String>> active = master.getActiveChannels();
		
		Map<String,Map<String,List<List<Object>>>> outdata = new HashMap<>();
		for (Map.Entry<Object[],List<String>> entry : active.entrySet()) {
			int conId = (Integer)entry.getKey()[0];
			String profile = (String)entry.getKey()[1];
			Map<String,List<List<Object>>> outprofile = new HashMap<>();
			outdata.put(profile, outprofile);
			
			for (String party : entry.getValue()) {
				windowQuery.bind(1, profile);
				windowQuery.bind(2, party);
				windowQuery.step();
				int window = windowQuery.columnInt(0);
				windowQuery.reset();
				
				List<List<Object>> outmsgs = new ArrayList<>();
				outprofile.put(party, outmsgs);
				messageQuery.bind(1, conId);
				messageQuery.bind(2, window);
				while (messageQuery.step())
					outmsgs.add(Arrays.asList(messageQuery.columnLong(0), messageQuery.columnString(1)));
				messageQuery.reset();
			}
		}
		database.dispose();
		
		Headers head = he.getResponseHeaders();
		head.set("Content-Type", "application/json; charset=UTF-8");
		head.set("Cache-Control", "no-cache");
		byte[] b = Json.serialize(outdata).getBytes(OutputWriterThread.UTF8_CHARSET);
		he.sendResponseHeaders(200, b.length);
		he.getResponseBody().write(b);
		he.close();
	}
	
	
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
		Object data = Json.parse(new String(bout.toByteArray(), OutputWriterThread.UTF8_CHARSET));
		String[] target = Json.getString(data, 0).split(":", 2);
		String line = Json.getString(data, 1);
		boolean status = master.sendMessage(target[1], target[0], line);
		
		Headers head = he.getResponseHeaders();
		head.set("Content-Type", "application/json; charset=UTF-8");
		head.set("Cache-Control", "no-cache");
		byte[] b = Boolean.toString(status).getBytes(OutputWriterThread.UTF8_CHARSET);
		he.sendResponseHeaders(200, b.length);
		he.getResponseBody().write(b);
		he.close();
	}
	
	
	private static void sendFile(File file, OutputStream out) throws IOException {
		InputStream in = new FileInputStream(file);
		try {
			byte[] buf = new byte[1024];
			while (true) {
				int n = in.read(buf);
				if (n == -1)
					break;
				out.write(buf, 0, n);
			}
		} finally {
			in.close();
		}
	}
	
}
