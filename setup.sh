# 
# Easy setup/compile script for MamIRC
# 
# This Unix shell script:
# 0. Clones the MamIRC Git repository into the current working directory.
# 1. Downloads and unpacks the Java library dependencies.
# 2. Compiles the Java source code (deleting any previous .class files).
# 
# You can run this script in a blank directory only containing this script,
# or you can run it in the root directory of the working tree of a MamIRC Git repository.
# Run this script with no arguments, in the working directory of your choice.
# 
# Copyright (c) Project Nayuki
# http://www.nayuki.io/page/mamirc-the-headless-irc-client
# https://github.com/nayuki/MamIRC
# 

# If any command returns a non-zero status, this script aborts immediately
set -e

# Clone the Git repository, if we are not in it already
if [ ! -e ".git" ]; then
	git clone "https://github.com/nayuki/MamIRC.git" "MamIRC/"
	mv -f MamIRC/* "MamIRC/.git" "."
	rm -rf "MamIRC/"
fi

# Go into bin/ subdirectory
if [ ! -e "bin/" ]; then
	mkdir bin
fi
cd bin

# Download a JAR file
if [ ! -e "nayuki-json-lib.jar" ]; then
	wget "http://www.nayuki.io/res/json-library-java/nayuki-json-lib.jar"
fi

# Download a ZIP file and extract
if [ ! -e "sqlite4java.jar" ]; then
	wget "https://d1.almworks.com/.files/sqlite4java/sqlite4java-392.zip"
	unzip "sqlite4java-392.zip" -d "."
	mv sqlite4java-392/*.jar sqlite4java-392/*.so "."
	rm -rf "sqlite4java-392/" "sqlite4java-392.zip"
fi
cd ..

# Delete all .class files in java/
find "java/" -name '*.class' | xargs rm -f -v

# Compile the MamIRC Connector and Processor
echo "Compiling Java source code..."
javac -sourcepath "java/" -d "java/" -cp "bin/sqlite4java.jar:bin/nayuki-json-lib.jar" \
	"java/io/nayuki/mamirc/connector/MamircConnector.java" \
	"java/io/nayuki/mamirc/processor/MamircProcessor.java"
