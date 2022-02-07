package io.nayuki.mamirc;

import java.io.File;


public final class Main {
	
	private static final File DEFAULT_DATABASE_FILE = new File("mamirc-user-data.sqlite");
	
	
	public static void main(String[] args) throws Exception {
		File dbFile;
		if (args.length == 0)
			dbFile = DEFAULT_DATABASE_FILE;
		else if (args.length == 1)
			dbFile = new File(args[0]);
		else {
			System.err.println("Usage: java io/nayuki/mamirc/Main [Database.sqlite]");
			System.exit(1);
			return;
		}
		
		try (Database db = new Database(dbFile)) {
			Core core = new Core(dbFile);
			core.reloadProfiles();
		}
	}
	
}
