MamIRC, the headless IRC client
===============================

MamIRC is an IRC client designed for users who demand portable web access, high connection uptime, and accurate logging.

![MamIRC screenshot](http://www.nayuki.io/res/mamirc-the-headless-irc-client/mamirc-screenshot.png)

The software comes in three parts - two backend Java programs and one frontend web page. The backend is intended to run on an always-on server computer, and the frontend can be accessed from any desktop or mobile web browser.

For setup instructions, see [doc/setup.markdown](https://github.com/nayuki/MamIRC/blob/master/doc/setup.markdown).


Architecture
------------

Software components and data flow:

      +--------+                                                   +-------------+  
      |  IRC   | <-                                             -> |   Desktop   |  
      | server |   \      +-----------+     +-----------+      /   | web browser |  
      +--------+    \     |  MamIRC   |     |  MamIRC   |     /    +-------------+  
                     +--> | Connector | <-> | Processor | <--+                      
      +--------+    /     +-----------+     +-----------+     \    +-------------+  
      |  IRC   |   /               |           ^               \   |   Mobile    |  
      | server | <-                v           |                -> | web browser |  
      +--------+                +-----------------+                +-------------+  
                                | SQLite database |                                 
                                +-----------------+                                 


Project links
-------------

* Home page: [http://www.nayuki.io/page/mamirc-the-headless-irc-client](http://www.nayuki.io/page/mamirc-the-headless-irc-client)

* Repository: [https://github.com/nayuki/MamIRC](https://github.com/nayuki/MamIRC)

* Documentation: [https://github.com/nayuki/MamIRC/tree/master/doc](https://github.com/nayuki/MamIRC/tree/master/doc)


License
-------

Copyright © Project Nayuki. All rights reserved. No warranty.  
