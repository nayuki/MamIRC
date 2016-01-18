MamIRC upgrade notes
====================

Instructions
------------

As different parts of the MamIRC codebase get updated, different procedures are needed to upgrade the software. Here is an overview:

* In the worst case as a last resort, you can preserve your SQLite database file and your configuration files, then follow the setup procedure to deploy the software from scratch. It is okay to delete all the program code and even the hidden ".git" repository directory. Note that if the MamIRC backend is running, you must terminate the relevant Java processes before starting the upgrade.

* Generally speaking, the hot-upgrade procedure works like this:

  0. Use `git log` or consult file timestamps to recall the date of your pre-upgrade version of MamIRC.

  0. Run `git pull` to get the latest version of the project. (If the merge is blocked, then you first need to save or discard your uncommited local edits.)

  0. If only the web UI changed, then refresh it in your browser and you are done (skip the rest of the list).

  0. Otherwise the Processor or Connector code has changed, then some work is needed.

  0. Refer to the setup procedure regarding how to compile Java code, and how to launch the Connector and Processor.

  0. Because the Connector or Processor or both have changed, you need to recompile the Java code.

  0. If the Connector changed, then restart both the Connector and Processor. You will disconnect from IRC servers.

  0. If only the Processor changed, then restart only the Processor. You will stay connected to IRC servers.

  0. Whenever the Processor is restarted, you must refresh the web UI.
  
  0. When the Processor is restarted, you will no longer see messages from previous socket connections. Although the old messages still exist buried in the database, the Processor is only designed to operate on current connections.

* Additionally, sometimes the backend configuration files change their formats. In that situation, you should:

  0. Run `git pull` to get the latest version of the project.

  0. Examine the new/updated sample configuration files in a text editor.

  0. Terminate the currently running Processor, and recompile the Java code.

  0. Manually edit your current configuration files to fit the new format.

  0. Launch the new Processor to use the new configuration files.

  0. Check that the launch was successful (i.e. it didn't immediately die due to a JSON syntax error), then refresh your web browser.


Change log
----------

Below is a list of dates where a change was made that requires special attention when upgrading. This includes significant updates to the Connector, Processor, configuration files, or other special instructions. This excludes web UI only updates, which simply need a web page reload. This also excludes functionally identical updates such as refactoring/renaming/reorganizing code, as well as non-functional changes to the documentation/comments/test-suites/supporting-scripts, etc.

The dates are listed in reverse-chronological order, and pertain to the 24-hour time period in the UTC time zone. (For example, the heading 2015-12-31 means "these comments pertain to commits that happened on 2015-12-31 between 00:00 and 23:59 UTC".) This log is not fully complete for dates before year 2016 because the software was not popular enough to require serious user support instructions at the time.

2016-01-18:

* Must recompile and restart Processor to support new web UI. (Feature: SVG media type.)

2016-01-17:

* Must recompile and restart Processor to support new web UI. (Feature: Mobile HTML page.)

2016-01-15:

* Should recompile and restart Processor to support new web UI. (Feature: Throttled multi-line sending.)

2015-12-29:

* May recompile and restart Processor to improve IRC protocol handling. (Feature: Detecting truncated self nickname.)

2015-12-28:

* Must recompile and restart Connector and Processor due to internal protocol change. (Feature: Querying the Connector without attachment.)

2015-12-27:

* Must edit configuration files to the new format, then recompile and restart Processor to support them. (Feature: Different split and new fields.)

2015-12-11:

* Should recompile and restart Processor to support new web UI. (Feature: Connecting/connected events.)
* May recompile and restart Connector to improve disconnect detection. (Feature: Periodic blank line pings.)

2015-12-04:

* May recompile and restart Processor to improve IRC protocol handling. (Feature: Detecting erroneous nickname at registration.)

2015-11-26:

* Should recompile and restart Processor to support new web UI. (Feature: Showing last-viewed window on load.)

2015-11-06:

* May recompile and restart Processor to improve IRC logging. (Feature: Query channel members daily.)

2015-11-05:

* Must recompile and restart Processor to support new web UI. (Feature: CSRF protection.)

2015-11-02:

* Should recompile and restart Processor to improve IRC protocol handling. (Feature: Fixing kick detection.)

2015-10-29:

* Must recompile and restart Processor to support new web UI. (Feature: Log-in page and cookies.)

2015-10-22:

* Must recompile and restart Processor to support new web UI. (Feature: Disconnection events.)

2015-10-20:

* Must recompile and restart Processor to support new web UI. (Feature: Server messages and channel users events.)

2015-10-14:

* Must recompile and restart Processor to support new web UI. (Feature: Clearer and more efficient JSON data format.)

2015-10-13:

* Should recompile and restart Processor to improve IRC protocol handling. (Feature: Detecting users being kicked.)

2015-10-09:

* Must recompile and restart Processor to support new web UI. (Feature: Line sequence numbers in windows.)
* May recompile and restart Processor to improve web serving. (Feature: HTTP DEFLATE compression.)

2015-10-06:

* Database format changed - the "connection opened" event adds the IP address as an argument. No current code reads this data or depends on it, but it could be useful in the future. For old data, no particular action is recommended because it is not possible to fill in the IP address after the fact. Future code should be aware of the data format change and may need to handle both formats.

2015-10-03:

* Should recompile and restart Processor to improve IRC handling. (Feature: Automatic reconnect upon disconnect.)

2015-09-30:

* Database format changed - the "connection connect" event adds the hostname, port, and use-SSL as more arguments. No current code reads this data or depends on it, but it could be useful in the future. If possible it is recommended to manually edit old entries, adding the 3 fields to upgrade the data to the new format. Future code may need to be aware of both variants of the data format.

2015-09-29:

* Must edit configuration file to the new format, then recompile and restart Processor to support it. (Feature: Custom web server port.)

2015-09-21:

* Must recompile and restart Connector and Processor due to change of responsibilities. (Feature: PING handling moved to the Connector.)

2015-09-18:

* Must recompile and restart Connector and Processor due to internal protocol change. (Feature: Removal of recent-events.)
* Must recompile and restart Processor to support new web UI. (Feature: Initial web UI.)

2015-09-17:

* May recompile and restart Processor to improve IRC handling. (Feature: SSL connection and NickServ password.)

2015-09-16:

* First version of the software was committed.

Note: The keywords {*must*, *must not*, *required*, *shall*, *shall not*, *should*, *should not*, *recommended*, *may*, *optional*} in this document are to be interpreted as described in [RFC 2119](http://tools.ietf.org/html/rfc2119).


Project links
-------------

* Home page: [http://www.nayuki.io/page/mamirc-the-headless-irc-client](http://www.nayuki.io/page/mamirc-the-headless-irc-client)

* GitHub repository: [https://github.com/nayuki/MamIRC](https://github.com/nayuki/MamIRC)

* Online documentation: [https://github.com/nayuki/MamIRC/tree/master/doc](https://github.com/nayuki/MamIRC/tree/master/doc)

Copyright © [Project Nayuki](http://www.nayuki.io/)
