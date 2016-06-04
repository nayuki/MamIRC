package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import io.nayuki.mamirc.common.BackendConfiguration;
import io.nayuki.mamirc.common.Utils;


public final class MamircProcessor {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws IOException {
		// Check the argument count
		if (args.length != 1) {
			System.err.println("Usage: java io/nayuki/mamirc/processor/MamircProcessor BackendConfig.json");
			System.exit(1);
		}
		
		// Set logging levels and behaviors
		{
			// Suppress all debug messages from sqlite4java module because we are not developing it
			Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);
			
			// Set the default logging level for this MamIRC Processor application (can be changed)
			Utils.logger.setLevel(Level.INFO);
			
			// Create new console output handler to overcome the default console handler only showing messages
			// down to Level.INFO (but not Level.FINE), and to define a more readable custom log line format
			Handler ch = new ConsoleHandler();
			ch.setLevel(Level.ALL);
			ch.setFormatter(new Formatter() {
				public String format(LogRecord rec) {
					String s = String.format("%tY-%<tm-%<td-%<ta %<tH:%<tM:%<tS %<tZ / %s / %s.%s() / %s%n",
							rec.getMillis(), rec.getLevel(), rec.getSourceClassName(), rec.getSourceMethodName(), rec.getMessage());
					if (rec.getThrown() != null) {
						StringWriter sw = new StringWriter();
						rec.getThrown().printStackTrace(new PrintWriter(sw));
						s += sw.toString();
					}
					return s;
				}
			});
			Utils.logger.setUseParentHandlers(false);
			Utils.logger.addHandler(ch);
			
			// Start a worker thread to read stdin, which lets the user change
			// the logging display level while the application is running
			Utils.startConsoleLogLevelChanger();
		}
		
		// Load config and start Processor
		Utils.logger.info("MamIRC Processor application starting");
		File backendConfigFile = new File(args[0]);
		BackendConfiguration backendConfig = new BackendConfiguration(backendConfigFile);
		Utils.logger.info("Backend configuration file parsed: " + backendConfigFile.getCanonicalPath());
		new MamircProcessor(backendConfig);
	}
	
	
	
	/*---- Constructor ----*/
	
	public MamircProcessor(BackendConfiguration backendConfig) {
		if (backendConfig == null)
			throw new NullPointerException();
	}
	
}
