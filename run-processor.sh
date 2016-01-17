# 
# Launch script for MamIRC Processor
# 
# This Unix shell script takes two file arguments (backend config, user config)
# and launches a MamIRC Processor. If another Processor is currently running with
# the same web server port, the new Processor will silently fail and terminate.
# In this case, you need to run the command "ps x" and kill the old Processor PID first.
# 
# The script the process in the background with nohup, takes care of
# the classpath requirements, and sets a memory limit on the JVM.
# 
# Copyright (c) Project Nayuki
# http://www.nayuki.io/page/mamirc-the-headless-irc-client
# https://github.com/nayuki/MamIRC
# 


# Check arguments
if [ "$#" -ne 2 ]; then
	echo "Usage: sh $0 BackendConfig.json UserConfig.json"
	exit 1
fi
if [ ! -f $1 -o ! -f $2 ]; then
	echo "Config file not found"
	exit 1
fi

# Set up paths
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$(pwd)"/bin/"
export CLASSPATH="java/":"bin/sqlite4java.jar":"bin/nayuki-json-lib.jar"

# Run command in background
nohup java -mx200M io/nayuki/mamirc/processor/MamircProcessor $1 $2 >/dev/null 2>&1 &
