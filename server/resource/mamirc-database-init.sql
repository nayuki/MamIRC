SELECT '==== This is a custom text format where each consecutive pair of SQL statements is separated by two newline sequences. It is because the chosen SQLite JDBC driver does not support executing multiple statements in one call. ===='

PRAGMA journal_mode = WAL

BEGIN TRANSACTION

CREATE TABLE configuration(
	key    TEXT NOT NULL  PRIMARY KEY,
	value  TEXT NOT NULL             )

CREATE TABLE irc_network_profiles(
	profile_id    INTEGER NOT NULL  PRIMARY KEY,
	profile_name  TEXT    NOT NULL             ,
	UNIQUE(profile_name)                       )

CREATE TABLE profile_configuration(
	profile_id          INTEGER NOT NULL  PRIMARY KEY REFERENCES irc_network_profiles,
	do_connect          INTEGER NOT NULL  CHECK(do_connect in (0,1))                 ,
	username            TEXT    NOT NULL                                             ,
	real_name           TEXT    NOT NULL                                             ,
	character_encoding  TEXT    NOT NULL                                             )

CREATE TABLE profile_servers(
	profile_id          INTEGER NOT NULL  REFERENCES irc_network_profiles   ,
	ordering            INTEGER NOT NULL                                    ,
	hostname            TEXT    NOT NULL                                    ,
	port                INTEGER NOT NULL  check(port between 0 and 65535)   ,
	tls_mode            INTEGER NOT NULL  check(tls_mode between 0 and 2)   ,
	PRIMARY KEY(profile_id, ordering)                                       )

CREATE TABLE profile_nicknames(
	profile_id          INTEGER NOT NULL  REFERENCES irc_network_profiles,
	ordering            INTEGER NOT NULL                                 ,
	nickname            TEXT    NOT NULL                                 ,
	PRIMARY KEY(profile_id, ordering)                                    ,
	UNIQUE(profile_id, nickname)                                         )

CREATE TABLE profile_after_registration_commands(
	profile_id          INTEGER NOT NULL  REFERENCES irc_network_profiles,
	ordering            INTEGER NOT NULL                                 ,
	command             TEXT    NOT NULL                                 ,
	PRIMARY KEY(profile_id, ordering)                                    )

CREATE TABLE connections(
	connection_id  INTEGER NOT NULL  PRIMARY KEY                    ,
	profile_id     INTEGER NOT NULL  REFERENCES irc_network_profiles)

CREATE TABLE connection_events(
	connection_id      INTEGER NOT NULL  REFERENCES connections,
	sequence           INTEGER NOT NULL  check(sequence >= 0)  ,
	timestamp_unix_ms  INTEGER NOT NULL                        ,
	data               BLOB    NOT NULL                        ,
	PRIMARY KEY(connection_id, sequence)                       )

CREATE TABLE message_windows(
	window_id       INTEGER NOT NULL  PRIMARY KEY                    ,
	profile_id      INTEGER NOT NULL  REFERENCES irc_network_profiles,
	display_name    TEXT    NOT NULL                                 ,
	canonical_name  TEXT    NOT NULL                                 )

CREATE INDEX message_windows_index_0 ON message_windows(
	canonical_name, window_id)

CREATE TABLE processed_messages(
	window_id  INTEGER NOT NULL  REFERENCES message_windows,
	sequence   INTEGER NOT NULL  check(sequence >= 0)      ,
	data       TEXT    NOT NULL                            ,
	PRIMARY KEY(window_id, sequence)                       )

COMMIT TRANSACTION
