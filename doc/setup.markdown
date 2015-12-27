MamIRC setup instructions
=========================

This document describes how to set up and run your own copy of MamIRC. A brief overview of the setup steps:

0. Download MamIRC source code.
0. Download JSON and SQLite libraries (dependencies).
0. Extract JAR and native binaries from SQLite package.
0. Compile all Java code, taking care to specify classpaths correctly.
0. Edit backend-config.json and user-config.json in a text editor.
0. Launch MamIRC Connector and MamIRC Processor Java programs in the background.
0. Set up SSL proxy in front of the MamIRC web server.
0. Open web browser to your MamIRC instance's URL.

Note that the Linux script handles some of these steps automatically, so you might not need to perform every step explicitly.

Platform support for backend software:

* Operating system: Linux, Microsoft Windows.
* CPU architectures: x86, x86-64.
* Not tested: Mac OS X, BSD, Solaris, ARM.

Web browser support for frontend web app:

* Working correctly: Mozilla Firefox (desktop), Google Chrome (desktop and Android).
* Not tested: Apple Safari (desktop and iOS), Microsoft Internet Explorer, Opera, Firefox (mobile).


Steps for Linux setup
---------------------

0. The machine must have Git and Java JDK (7+) installed. You need basic proficiency with the command line.

0. Create a new empty directory for MamIRC.

0. Download the easy shell script: `wget https://raw.githubusercontent.com/nayuki/MamIRC/master/setup.sh`

0. Run the script and watch for error messages. The script will download the code and libraries, manipulate directories, and compile the Java code. Command: `sh setup.sh`

0. Open "backend-config-sample.json" in a text editor and check all 5 settings. The default settings are mostly fine, but you must change the web UI password to one of your choice; this password protects unauthorized users from accessing your MamIRC instance. Ensure that the port numbers do not conflict with any other servers you run. It is optional to change Connector password, because the Connector only accepts connections coming from programs running on the local machine (never from the Internet). Finally, it's recommended to rename the file to remove the "-sample" in the name.

0. Now we launch the MamIRC Connector software, with the configuration file name as an argument, like this: `sh run-connector.sh backend-config.json`

0. If the launch was successful, the `java` process will run forever, and can be seen in the process list produced by `ps x`. Otherwise the process dies immediately due to a configuration file syntax error, file I/O exception, socket port in use, Java class loading failure, SQLite native library loading failure, etc. (check using `ps x`). To debug an unsuccessful launch, you'd need to type out the `java` command in run-connector.sh, minus the initial `nohup` and trailing `&`.

0. Open "user-config-sample.json" in a text editor, follow the examples, and edit the IRC networks you want to connect to. Fill in the fields for the IRC servers, your nickname, and the channels you want to join. Finally, it's recommended to rename the file to remove the "-sample" in the name.

0. Now we launch the MamIRC Processor software, with both configuration file names as arguments in order, like this: `sh run-processor.sh backend-config.json user-config.json`

0. Again if the launch was successful, this second `java` process will run forever. If unsuccessful, you'd need to type out the `java` command in run-processor.sh, minus the initial `nohup` and trailing `&`, to help debug what went wrong with the software run. If successful, then your MamIRC Processor should have connected to your designated IRC networks and joined your designated channels, all silently in the background.

0. Note that at this point, the MamIRC Processor only listens for HTTP connections from the local machine; you cannot access your MamIRC web UI from the Internet or even LAN. To change this behavior, read the section below titled "Configuring the web server".

0. Finally we view and interact with MamIRC through a web browser. Navigate to http://localhost:11972/ , according to the web server port number you set. Enter your password, and follow the graphical user interface. The only significant non-obvious UI feature is some items on screen be right-clicked to get a menu of available commands.


Steps for Windows setup
-----------------------

0. The machine must have Java JDK (7+) installed. You need moderate proficiency with the command line.

0. Download the [MamIRC ZIP file](https://github.com/nayuki/MamIRC/archive/master.zip) from GitHub, and unzip it into a new directory for MamIRC.

0. Download the [Nayuki JSON library JAR](http://www.nayuki.io/page/json-library-java) and put it in the MamIRC root directory.

0. Download the [ALM Works Sqlite4java ZIP](https://bitbucket.org/almworks/sqlite4java), and extract the JAR and DLL files into the MamIRC root directory.

0. Compile all the MamIRC Java source files from the command line, or by setting up a project in a Java IDE like Eclipse or NetBeans. Make sure to add both JARs to the classpath.

0. If choosing to compile from the command line (ensuring that your PATH can reach `javac`), first make a directory named "bin" in the project root, then run this command: `javac -cp nayuki-json-lib.jar;sqlite4java.jar -sourcepath src/ -d bin/ src/io/nayuki/mamirc/connector/MamircConnector.java src/io/nayuki/mamirc/processor/MamircProcessor.java`

0. Open "backend-config-sample.json" in a text editor and check all 5 settings. The default settings are mostly fine, but you must change the web UI password to one of your choice; this password protects unauthorized users from accessing your MamIRC instance. Ensure that the port numbers do not conflict with any other servers you run. It is optional to change Connector password, because the Connector only accepts connections coming from programs running on the local machine (never from the Internet). Finally, it's recommended to rename the file to remove the "-sample" in the name.

0. Now we launch the MamIRC Connector software, with the configuration file name as an argument, like this: `javaw -cp bin/;nayuki-json-lib.jar;sqlite4java.jar io/nayuki/mamirc/connector/MamircConnector backend-config.json`

0. If the launch was successful, the javaw.exe process will run forever, and can be seen in the list in Windows Task Manager. Otherwise the process dies immediately due to a configuration file syntax error, file I/O exception, socket port in use, Java class loading failure, SQLite native library loading failure, etc. (check using Task Manager). To debug an unsuccessful launch, it is helpful to replace `javaw` with `java`, so that error messages can be seen on the console.

0. Open "user-config-sample.json" in a text editor, follow the examples, and edit the IRC networks you want to connect to. Fill in the fields for the IRC servers, your nickname, and the channels you want to join. Finally, it's recommended to rename the file to remove the "-sample" in the name.

0. Now we launch the MamIRC Processor software, with both configuration file names as arguments in order, like this: `javaw -cp bin/;nayuki-json-lib.jar;sqlite4java.jar io/nayuki/mamirc/processor/MamircProcessor backend-config.json user-config.json`

0. Again if the launch was successful, this second javaw.exe process will run forever. If unsuccessful, you can replace `javaw` with `java` to help debug what went wrong with the software run. If successful, then your MamIRC Processor should have connected to your designated IRC networks and joined your designated channels, all silently in the background.

0. Note that at this point, the MamIRC Processor only listens for HTTP connections from the local machine; you cannot access your MamIRC web UI from the Internet or even LAN. To change this behavior, read the section below titled "Configuring the web server".

0. Finally we view and interact with MamIRC through a web browser. Navigate to http://localhost:11972/ , according to the web server port number you set. Enter your password, and follow the graphical user interface. The only significant non-obvious UI feature is some items on screen be right-clicked to get a menu of available commands.


Configuring the web server
--------------------------

The MamIRC Processor has a built-in HTTP web server. The server port number is set in the "backend-config.json" configuration file. By default, the web server only listens for connections coming from the local machine, not from the LAN or Internet. This safety feature forces you to consider how you want to secure your communications.

Local-only: If you are running the MamIRC Processor and a web browser on the same machine, then there is nothing to configure and you are good to go. This case makes sense if you are running a local desktop computer that is always on and has a stable Internet connection, or if you are running MamIRC for development.

Unencrypted HTTP (dangerous!): The lazy and insecure to make your web server publicly visible is to hack the Java source code, recompile, and launch the Processor. The line that needs to be changed is in MessageHttpServer.java, `server = HttpServer.create(new InetSocketAddress("localhost", port), 0);`, changing `"localhost"` to `"0.0.0.0"`. Recompile the Java code by running `sh setup.sh` (Linux) or some specific incantation of `javac`. Now you can access the web server from a web browser anywhere, and all communications between the server and client are in unencrypted HTTP. Please, please don't do this for serious use! Only do this for short test runs. Your password will be sent in cleartext in every HTTP request, and all messages you send and receive are in cleartext. You will harm your privacy and the privacy of everyone who talks to you. Furthermore, an attacker can actively control your MamIRC session, send messages as your identity, join/part channels, etc.

Stunnel HTTPS: One secure approach is to put an [stunnel](https://www.stunnel.org/) reverse proxy server in front of your MamIRC web server. On Linux download the source code and run `make`, or on Windows download the EXE. Generate a new SSL certificate for yourself ([instructions](http://www.akadia.com/services/ssh_test_certificate.html) using OpenSSL), write a simple configuration file for stunnel (see [examples](https://www.stunnel.org/static/stunnel.html#EXAMPLES)), and run stunnel with the config. It's okay to use a self-signed certificate (you need to jump through some scary warnings in your web browser), but you could get your SSL certificate signed free of charge from some vendors.

Nginx HTTPS: Another secure approach is to use Nginx as the reverse proxy. This is helpful if you are already running Nginx for web hosting, or you are comfortable setting it up. Follow the same steps to generate an SSL certificate, and then add about 10 lines to your Nginx site configuration file to enable the proxy.

SSH proxy: If MamIRC is running on a Linux machine, you can log into the machine through SSH, set up a static tunnel, and point your web browser to the tunnel.

Remember that if you are running a server behind a NAT router, you are responsible for configuring the port forwarding.


Project links
-------------

* Home page: [http://www.nayuki.io/page/mamirc-the-headless-irc-client](http://www.nayuki.io/page/mamirc-the-headless-irc-client)

* GitHub repository: [https://github.com/nayuki/MamIRC](https://github.com/nayuki/MamIRC)

* Online documentation: [https://github.com/nayuki/MamIRC/tree/master/doc](https://github.com/nayuki/MamIRC/tree/master/doc)
