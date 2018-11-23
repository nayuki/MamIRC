# 
# MamIRC
# Copyright (c) Project Nayuki
# 
# https://www.nayuki.io/page/mamirc-the-headless-irc-client
# https://github.com/nayuki/MamIRC
# 

import contextlib, pathlib, sqlite3, sys
if sys.version_info[ : 3] < (3, 4, 0):
	raise RuntimeError("Requires Python 3.4+")


def main(argv):
	if len(argv) not in (2, 3, 4):
		sys.exit("""Usage:
  python {0} Config.sqlite            # List all key-value pairs
  python {0} Config.sqlite Key        # Delete a key
  python {0} Config.sqlite Key Value  # Set new/existing key to value""".format(argv[0]))
	
	file = pathlib.Path(argv[1])
	
	if len(argv) == 2:  # List all key-value pairs
		if not file.is_file():
			sys.exit("Error: Database file does not exist")
		
		with contextlib.closing(sqlite3.connect("file:{}?mode=ro".format(file), uri=True)) as con:
			cur = con.cursor()
			cur.execute("SELECT key, value FROM main ORDER BY key ASC")
			while True:
				row = cur.fetchone()
				if row is None:
					break
				print(" = ".join(row))
		
	else:
		key = argv[2]
		if key == "file type":
			sys.exit('Error: Invalid key: "{}"'.format(key))
		
		with contextlib.closing(sqlite3.connect("file:{}?mode=rwc".format(file), uri=True)) as con:
			cur = con.cursor()
			cur.execute(
"""CREATE TABLE IF NOT EXISTS main(
	key TEXT NOT NULL PRIMARY KEY,
	value TEXT NOT NULL
)""")
			cur.execute("INSERT OR IGNORE INTO main VALUES('file type', 'MamIRC configuration')")
			
			if len(argv) == 3:  # Delete a key
				cur.execute("DELETE FROM main WHERE key=?", (key,))
			elif len(argv) == 4:  # Set new/existing key to value
				cur.execute("INSERT OR REPLACE INTO main VALUES(?,?)", (key, argv[3]))
			con.commit()


if __name__ == "__main__":
	main(sys.argv)
