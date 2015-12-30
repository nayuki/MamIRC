# 
# This Unix shell script takes one argument (connector config)
# and launches a MamIRC connector. If another connector is currently running
# with the same server port, the new connector will silently fail. In this case,
# you need to run the command "ps x" and kill the old connector PID first.
# 
# The script the process in the background with nohup, takes care of
# the classpath requirements, and sets a memory limit on the JVM.
# 
# MamIRC
# Copyright (c) Project Nayuki
# 
# http://www.nayuki.io/page/mamirc-the-headless-irc-client
# https://github.com/nayuki/MamIRC
# 


# Check arguments
if [ "$#" -ne 2 ]; then
	echo "Usage: sh $0 BackendConfig.json UserConfig.json"
	exit 1
fi
if [ ! -f $1 ]; then
	echo "Config file not found"
	exit 1
fi
if [ ! -f $2 ]; then
	echo "Config file not found"
	exit 1
fi

# Run command in background
nohup java -mx50M -cp "bin/:sqlite4java.jar:nayuki-json-lib.jar" io/nayuki/mamirc/processor/MamircProcessor $1 $2 >/dev/null 2>&1 &
