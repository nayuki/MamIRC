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
import java.util.concurrent.Executors;
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
	
	public MessageHttpServer(MamircProcessor master, int port) throws IOException {
		this.master = master;
		server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		
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
		
		server.createContext("/get-new-messages.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				try {
					handleGetNewMessages(he);
				} catch (SQLiteException e) {
					e.printStackTrace();
					he.sendResponseHeaders(500, 0);
					he.close();
				} catch (Exception e) {}
			}
		});
		
		server.createContext("/send-message.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				handleSendMessage(he);
			}
		});
		
		server.setExecutor(Executors.newFixedThreadPool(4));
	}
	
	
	/*---- Methods ----*/
	
	private void handleGetMessages(HttpExchange he) throws IOException, SQLiteException {
		SQLiteConnection database = new SQLiteConnection(databaseFile);
		database.open(false);
		SQLiteStatement windowQuery = database.prepare("SELECT id FROM windows WHERE profile=? AND party=?");
		SQLiteStatement messageQuery = database.prepare("SELECT sequence, timestamp, line FROM messages WHERE connectionId=? AND windowId=? ORDER BY sequence ASC");
		Map<Object[],List<String>> active = master.getActiveChannels();
		
		// For each network profile
		Map<String,Map<String,Object>> outData = new HashMap<>();
		for (Map.Entry<Object[],List<String>> entry : active.entrySet()) {
			int conId = (Integer)entry.getKey()[0];
			String profile = (String)entry.getKey()[1];
			Map<String,Object> outProfile = new HashMap<>();
			
			Map<String,List<List<Object>>> outWindows = new HashMap<>();
			int maxSeq = -1;
			for (String party : entry.getValue()) {
				windowQuery.bind(1, profile);
				windowQuery.bind(2, party);
				windowQuery.step();
				int window = windowQuery.columnInt(0);
				windowQuery.reset();
				
				List<List<Object>> outMsgs = new ArrayList<>();
				messageQuery.bind(1, conId);
				messageQuery.bind(2, window);
				while (messageQuery.step()) {
					outMsgs.add(Arrays.asList(messageQuery.columnLong(1), messageQuery.columnString(2)));
					maxSeq = Math.max(messageQuery.columnInt(0), maxSeq);
				}
				messageQuery.reset();
				outWindows.put(party, outMsgs);
			}
			outProfile.put("windows", outWindows);
			
			outProfile.put("connection-id", conId);
			outProfile.put("max-sequence", maxSeq);
			outData.put(profile, outProfile);
		}
		database.dispose();
		
		Headers head = he.getResponseHeaders();
		head.set("Content-Type", "application/json; charset=UTF-8");
		head.set("Cache-Control", "no-cache");
		byte[] b = Json.serialize(outData).getBytes(OutputWriterThread.UTF8_CHARSET);
		he.sendResponseHeaders(200, b.length);
		he.getResponseBody().write(b);
		he.close();
	}
	
	
	private void handleGetNewMessages(HttpExchange he) throws IOException, SQLiteException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		InputStream in = he.getRequestBody();
		byte[] buf = new byte[1024];
		while (true) {
			int n = in.read(buf);
			if (n == -1)
				break;
			bout.write(buf, 0, n);
		}
		Object queryData = Json.parse(new String(bout.toByteArray(), OutputWriterThread.UTF8_CHARSET));
		
		List<Object[]> query = new ArrayList<>();
		for (String profile : Json.getMap(queryData).keySet()) {
			int conId   = Json.getInt(queryData, profile, 0);
			int lastSeq = Json.getInt(queryData, profile, 1);
			query.add(new Object[]{profile, conId, lastSeq});
		}
		
		Object outData = getNewMessages(query);
		long endTime = System.currentTimeMillis() + 60000;
		while (outData == null) {
			long remainTime = endTime - System.currentTimeMillis();
			if (remainTime <= 0)
				break;
			try {
				synchronized(this) {
					this.wait(remainTime);
				}
				Thread.sleep(500);  // Wait for database to write and commit
			} catch (InterruptedException e) {}
			outData = getNewMessages(query);
		}
		if (outData == null)
			outData = new HashMap<String,Object>();
		
		Headers head = he.getResponseHeaders();
		head.set("Content-Type", "application/json; charset=UTF-8");
		head.set("Cache-Control", "no-cache");
		byte[] b = Json.serialize(outData).getBytes(OutputWriterThread.UTF8_CHARSET);
		he.sendResponseHeaders(200, b.length);
		he.getResponseBody().write(b);
		he.close();
	}
	
	
	private Map<String,Map<String,Object>> getNewMessages(List<Object[]> inputQuery) throws SQLiteException {
		SQLiteConnection database = new SQLiteConnection(databaseFile);
		database.open(false);
		SQLiteStatement messageQuery = database.prepare("SELECT sequence, windowId, timestamp, line FROM messages WHERE connectionId=? AND sequence>? ORDER BY windowId ASC, sequence ASC");
		SQLiteStatement windowQuery = database.prepare("SELECT party FROM windows WHERE id=?");
		
		Map<String,Map<String,Object>> outData = new HashMap<>();
		for (Object[] query : inputQuery) {
			Integer conId = (Integer)query[1];
			messageQuery.bind(1, conId);
			messageQuery.bind(2, (Integer)query[2]);
			
			Map<String,List<List<Object>>> outWindows = new HashMap<>();
			int maxSeq = -1;
			int currentWindow = -1;
			List<List<Object>> currentMessages = null;
			while (messageQuery.step()) {
				int winId = messageQuery.columnInt(1);
				if (winId != currentWindow) {
					windowQuery.bind(1, winId);
					if (!windowQuery.step())
						throw new RuntimeException();
					String party = windowQuery.columnString(0);
					currentWindow = winId;
					windowQuery.reset();
					
					currentMessages = new ArrayList<>();
					outWindows.put(party, currentMessages);
				}
				
				currentMessages.add(Arrays.asList(messageQuery.columnLong(2), messageQuery.columnString(3)));
				maxSeq = Math.max(messageQuery.columnInt(0), maxSeq);
			}
			messageQuery.reset();
			
			if (!outWindows.isEmpty()) {
				Map<String,Object> outProfile = new HashMap<>();
				outProfile.put("windows", outWindows);
				outProfile.put("connection-id", conId);
				outProfile.put("max-sequence", maxSeq);
				outData.put((String)query[0], outProfile);
			}
		}
		database.dispose();
		
		if (!outData.isEmpty())
			return outData;
		else
			return null;
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
