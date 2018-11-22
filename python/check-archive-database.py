# 
# Check integrity of MamIRC archive database
# 
# Usage: python check-archive-database.py MamircArchive.sqlite
# This program reads from the given database file and prints a bunch of info/warning/error messages to standard error.
# This exits with status code 0 if and only if no known forms of corruption are found in the database.
# 
# It is semi-safe to run this on a database that is actively being used by a MamIRC Connector process.
# No corrupt data will be read or written. But if this reader takes more time
# than the SQLite busy timeout, then it can cause the writer to throw an error.
# 
# Copyright (c) Project Nayuki
# 
# https://www.nayuki.io/page/mamirc-the-headless-irc-client
# https://github.com/nayuki/MamIRC
# 

import contextlib, pathlib, sqlite3, sys
if sys.version_info[ : 3] < (3, 4, 0):
	raise RuntimeError("Requires Python 3.4+")


def main(argv):
	# Check command line arguments
	if len(argv) != 2:
		sys.exit("Usage: python {} MamircArchive.sqlite".format(argv[0]))
	filepath = pathlib.Path(argv[1])
	if not filepath.is_file():
		sys.exit("[ERROR] File does not exist: {}".format(filepath))
	
	# Open database file
	print("[INFO] Database file: {}".format(filepath.resolve()), file=sys.stderr)
	with contextlib.closing(sqlite3.connect("file:{}?mode=ro".format(filepath), uri=True)) as con:
		cur = con.cursor()
		
		# Perform SQLite's built-in data structure check
		cur.execute("PRAGMA integrity_check")
		if cur.fetchone()[0] != "ok":
			sys.exit("[ERROR] SQLite integrity check failed. Aborting")
		print("[INFO] SQLite integrity check passed", file=sys.stderr)
		
		# Print basic statistics
		cur.execute("SELECT count(*) FROM events")
		print("[INFO] Number of events: {}".format(cur.fetchone()[0]), file=sys.stderr)
		cur.execute("SELECT max(connectionId) FROM events")
		print("[INFO] Highest connection ID: {}".format(cur.fetchone()[0]), file=sys.stderr)
		
		# Check all events for each connection ID
		conidcur = con.cursor()
		conidcur.execute("SELECT DISTINCT connectionId FROM events ORDER BY connectionId ASC")
		haserror = False
		for (conid,) in _row_iterator(conidcur):
			haserror = _check_connection_id(conid, cur) or haserror
		
		# Print final summary
		if haserror:
			sys.exit("[ERROR] Some integrity checks failed for this MamIRC archive database")
		else:
			print("[INFO] Integrity checks passed for this MamIRC archive database", file=sys.stderr)


def _check_connection_id(conid, dbcur):
	# Check negative connection ID
	if conid < 0:
		print("[ERROR] Negative connection ID: {}".format(conid), file=sys.stderr)
		haserror = True
	
	# Check all events for this connection in sequential order
	dbcur.execute("SELECT sequence, type, data FROM events WHERE connectionId=? ORDER BY sequence ASC", (conid,))
	state = 0  # 0 = init, 1 = connecting, 2 = opened, 3 = closed
	nextseq = 0
	haserror = False
	for (seq, type, data) in _row_iterator(dbcur):
		# Handle sequence number
		if seq != nextseq:
			print("[ERROR] Connection ID {} has a sequence number gap at {}".format(conid, nextseq), file=sys.stderr)
			haserror = True
		nextseq = seq + 1
		
		# Handle event type
		if not (0 <= type <= 2):
			print("[ERROR] Invalid event type {} on connection ID {} at sequence number {}".format(type, conid, seq), file=sys.stderr)
			haserror = True
		if type == 0:  # Connection events always have data in UTF-8
			data = data.decode("UTF-8")
			if data.startswith("connect"):
				data = data.split(" ", 4)
				if len(data) != 5:
					print("[ERROR] Invalid event data on connection ID {} at sequence number {}".format(conid, seq), file=sys.stderr)
					haserror = True
			else:
				data = data.split(" ", 1)
				if data[0] not in ("opened", "disconnect", "closed") or (data[0] == "opened" and len(data) != 2) or (data[0] in ("disconnect", "closed") and len(data) != 1):
					print("[ERROR] Invalid event data on connection ID {} at sequence number {}".format(conid, seq), file=sys.stderr)
					haserror = True
		
		# Drive the state machine
		if state == 0:
			if type == 0 and len(data) == 5:
				state = 1
			else:
				print('[ERROR] Expected "connect" event on connection ID {} at sequence number {}'.format(conid, seq), file=sys.stderr)
				return True
		elif state == 1:
			if type == 0:
				if data[0] == "opened":
					state = 2
				elif data[0] == "disconnect":
					pass
				elif data[0] == "closed":
					state = 3
				else:
					print("[ERROR] Invalid event occurred on connection ID {} at sequence number {} based on the connection state".format(conid, seq), file=sys.stderr)
					haserror = True
			else:
				print("[ERROR] Invalid event occurred on connection ID {} at sequence number {} based on the connection state".format(conid, seq), file=sys.stderr)
				haserror = True
		elif state == 2:
			if type == 1 or type == 2 or (type == 0 and data[0] == "disconnect"):
				pass
			elif type == 0 and len(data) == 1 and data[0] == "closed":
				state = 3
			else:
				print("[ERROR] Invalid event occurred on connection ID {} at sequence number {} based on the connection state".format(conid, seq), file=sys.stderr)
				haserror = True
		elif state == 3:
			print("[ERROR] Invalid event occurred on connection ID {} at sequence number {} based on the connection state".format(conid, seq), file=sys.stderr)
			haserror = True
		else:
			raise AssertionError("Invalid state")
	return haserror


# Generates a row tuple for each fetchone() call that doesn't return None.
def _row_iterator(dbcur):
	while True:
		rows = dbcur.fetchmany(1000)
		if len(rows) == 0:
			break
		yield from rows


if __name__ == "__main__":
	main(sys.argv)
