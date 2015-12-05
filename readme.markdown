MamIRC, the headless IRC client
===============================

MamIRC is an IRC client designed for users who value portable web access, high uptime, and accurate logging.

It comes in two parts - a Java backend and a web page frontend. The backend is intended to run on an always-on server computer, and the frontend can be accessed by a desktop or mobile web browser.

For setup instructions, see [setup.markdown](https://github.com/nayuki/MamIRC/blob/master/doc/setup.markdown).

Architecture of the software and data flow:

                                                                                    
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

----

Copyright © Project Nayuki. All rights reserved. No warranty.  
Author's home page: http://www.nayuki.io/
