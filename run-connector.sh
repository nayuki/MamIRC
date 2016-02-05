# 
# Launch script for MamIRC Connector
# 
# This Unix shell script takes one file argument (backend config) and launches
# a MamIRC Connector. If another Connector is currently running with the same
# server port, the new Connector will silently fail and terminate. In this case,
# you need to run the command "ps x" and kill the old Connector PID first.
# 
# The script runs the process in the background with nohup, takes care of
# the classpath requirements, and sets a memory limit on the JVM.
# 
# Copyright (c) Project Nayuki
# https://www.nayuki.io/page/mamirc-the-headless-irc-client
# https://github.com/nayuki/MamIRC
# 


# Check arguments
if [ "$#" -ne 1 ]; then
	echo "Usage: sh $0 BackendConfig.json"
	exit 1
fi
if [ ! -f $1 ]; then
	echo "Config file not found"
	exit 1
fi

# Set up paths
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$(pwd)"/bin/"
export CLASSPATH="java/":"bin/sqlite4java.jar":"bin/nayuki-json-lib.jar"

# Run command in background
nohup java -mx50M io/nayuki/mamirc/connector/MamircConnector $1 >/dev/null 2>&1 &
