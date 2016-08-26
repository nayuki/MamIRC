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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.almworks.sqlite4java.SQLiteException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.nayuki.json.Json;
import io.nayuki.mamirc.common.Utils;


public class WebServer {
	
	public WebServer(int port, final MamircProcessor master, final MessageManager msgMgr) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		
		server.createContext("/log-viewer", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				he.getResponseHeaders().set("Content-Type", "application/xhtml+xml");
				he.sendResponseHeaders(200, 0);
				Files.copy(new File("web/log-viewer.html").toPath(), he.getResponseBody());
				he.close();
			}
		});
		
		server.createContext("/log-viewer.css", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				he.getResponseHeaders().set("Content-Type", "text/css");
				he.sendResponseHeaders(200, 0);
				Files.copy(new File("web/log-viewer.css").toPath(), he.getResponseBody());
				he.close();
			}
		});
		
		server.createContext("/log-viewer.js", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				he.getResponseHeaders().set("Content-Type", "application/javascript");
				he.sendResponseHeaders(200, 0);
				Files.copy(new File("web/log-viewer.js").toPath(), he.getResponseBody());
				he.close();
			}
		});
		
		server.createContext("/get-window-list.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				try {
					Object respData = msgMgr.listAllWindowsAsJson();
					byte[] respBytes = Utils.toUtf8(Json.serialize(respData));
					he.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
					he.sendResponseHeaders(200, respBytes.length);
					he.getResponseBody().write(respBytes);
					he.close();
				} catch (SQLiteException e) {
					he.sendResponseHeaders(500, -1);
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
					byte[] respBytes = Utils.toUtf8(Json.serialize(respData));
					
					he.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
					he.sendResponseHeaders(200, respBytes.length);
					he.getResponseBody().write(respBytes);
					he.close();
				} catch (SQLiteException e) {
					he.sendResponseHeaders(500, -1);
				}
			}
		});
		
		server.createContext("/network-profiles", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				he.getResponseHeaders().set("Content-Type", "application/xhtml+xml");
				he.sendResponseHeaders(200, 0);
				Files.copy(new File("web/network-profiles.html").toPath(), he.getResponseBody());
				he.close();
			}
		});
		
		server.createContext("/network-profiles.css", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				he.getResponseHeaders().set("Content-Type", "text/css");
				he.sendResponseHeaders(200, 0);
				Files.copy(new File("web/network-profiles.css").toPath(), he.getResponseBody());
				he.close();
			}
		});
		
		server.createContext("/network-profiles.js", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				he.getResponseHeaders().set("Content-Type", "application/javascript");
				he.sendResponseHeaders(200, 0);
				Files.copy(new File("web/network-profiles.js").toPath(), he.getResponseBody());
				he.close();
			}
		});
		
		server.createContext("/get-network-profiles.json", new HttpHandler() {
			public void handle(HttpExchange he) throws IOException {
				Object respData = master.getNetworkProfilesAsJson();
				byte[] respBytes = Utils.toUtf8(Json.serialize(respData));
				he.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
				he.sendResponseHeaders(200, respBytes.length);
				he.getResponseBody().write(respBytes);
				he.close();
			}
		});
		
		ExecutorService executor = Executors.newFixedThreadPool(10);
		server.setExecutor(executor);
		server.start();
	}
	
}
