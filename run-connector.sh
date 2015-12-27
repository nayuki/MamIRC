# 
# This Unix shell script takes two argument (connector config, processor config)
# and launches a MamIRC processor. If another processor is currently running with
# the same web server port, the new processor will silently fail. In this case,
# you need to run the command "ps x" and kill the old processor PID first.
# 
# The script runs the process in the background with nohup, takes care of
# the classpath requirements, and sets a memory limit on the JVM.
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

# Run command in background
nohup java -mx50M -cp "bin/:sqlite4java.jar:nayuki-json-lib.jar" io/nayuki/mamirc/connector/MamircConnector $1 >/dev/null 2>&1 &
