# 
# Generate connection statistics
# 
# Usage: python generate-connection-stats.py MamircArchive.sqlite Output.html
# This program reads from the given database file and writes to the given HTML file.
# 
# It is safe to run this on a database that is actively being used by a MamIRC Connector
# process. The reader won't block the writer, and no corrupt data will be read or written.
# 
# Copyright (c) Project Nayuki
# 
# https://www.nayuki.io/page/mamirc-the-headless-irc-client
# https://github.com/nayuki/MamIRC
# 

import contextlib, datetime, sqlite3, sys
if sys.version_info[ : 3] < (3, 2, 0):
	sys.exit("Error: Python 3.2+ required")


def main(argv):
	if len(argv) != 3:
		sys.exit("Usage: python {} MamircArchive.sqlite Output.html".format(argv[0]))
	
	with open(argv[2], "wt", encoding="UTF-8") as fout:
		fout.write(
"""<!DOCTYPE html>
<html>
	<head>
		<meta charset="UTF-8">
		<title>MamIRC archive – Connection statistics</title>
		<style type="text/css">
			html {
				background-color: #FFFFFF;
				font-family: sans-serif;
				color: #000000;
			}
			table {
				border-collapse: collapse;
			}
			tr:hover {
				background-color: #F0F0F0;
			}
			th, td {
				padding: 0.3em 0.5em;
				border: 0.08em solid #E0E0E0;
			}
			td:nth-child(1), td:nth-child(4), td:nth-child(7) {
				text-align: right;
			}
			td span + span {
				margin-left: 0.3em;
			}
		</style>
	</head>
	<body>
		<h1>MamIRC archive – Connection statistics</h1>
		<table>
			<thead>
				<tr>
					<th>Connection ID</th>
					<th>Profile name</th>
					<th>Target server</th>
					<th>Amount of activity</th>
					<th>First timestamp</th>
					<th>Last timestamp</th>
					<th>Duration</th>
				</tr>
			</thead>
			<tbody>
""")
		
		with contextlib.closing(sqlite3.connect("file:" + argv[1] + "?mode=ro", uri=True)) as con:
			cur = con.cursor()
			nextconid = 0
			while True:
				
				cur.execute("""SELECT connectionId FROM events
					WHERE connectionId>=? ORDER BY connectionId ASC LIMIT 1""", (nextconid,))
				row = cur.fetchone()
				if row is None:
					break
				conid = row[0]
				
				cur.execute("""SELECT timestamp, type, data FROM events
					WHERE connectionId=? AND sequence=0""", (conid,))
				row = cur.fetchone()
				if row is None or row[1] != 0:
					raise ValueError("Invalid data in database")
				starttimestamp = UNIX_EPOCH + datetime.timedelta(milliseconds=row[0])
				
				datastr = row[2].decode("UTF-8")
				parts = datastr.split(" ", 4)
				if parts[0] != "connect":
					raise ValueError("Invalid data in database")
				
				cur.execute("""SELECT sequence, timestamp FROM events WHERE connectionId=?
					AND sequence=(SELECT max(sequence) FROM events WHERE connectionId=?)""", (conid, conid))
				row = cur.fetchone()
				if row is None:
					raise AssertionError()
				lastsequence = row[0]
				endtimestamp = UNIX_EPOCH + datetime.timedelta(milliseconds=row[1])
				
				cells = [
					group_digits(conid),
					parts[4],
					" ".join(parts[1 : 4]),
					group_digits(lastsequence + 1) + " events",
					starttimestamp.strftime(TIMESTAMP_FORMAT),
					endtimestamp  .strftime(TIMESTAMP_FORMAT),
					"{:.3f} days".format((endtimestamp - starttimestamp).total_seconds() / 86400),
				]
				fout.write("				<tr>" + "".join("<td>{}</td>".format(c) for c in cells) + "</tr>\n")
				
				nextconid = conid + 1
		
		fout.write(
"""			</tbody>
		</table>
	</body>
</html>
""")


def group_digits(n):
	temp = str(n)
	result = ""
	end = len(temp)
	while end > 0:
		start = max(end - 3, 0)
		result = "<span>{}</span>".format(temp[start : end]) + result
		end = start
	return result


UNIX_EPOCH = datetime.datetime(1970, 1, 1)
TIMESTAMP_FORMAT = "%Y-%m-%d-%a %H:%M:%S UTC"


if __name__ == "__main__":
	main(sys.argv)
