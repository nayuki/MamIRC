# 
# This Unix shell script downloads MamIRC and its dependencies into the current
# working directory, extracts a few files, and compiles the Java source code.
# 
# This can be run in a blank directory or in a directory that has already been set up previously.
# This script can also be run after updating the source code through an existing Git repository.
# 
# MamIRC
# Copyright (c) Project Nayuki
# 
# http://www.nayuki.io/page/mamirc-the-headless-irc-client
# https://github.com/nayuki/MamIRC
# 


# Clone the Git repository, if we are not in it already
if [ ! -e ".git" ]; then
	git clone "https://github.com/nayuki/MamIRC.git" MamIRC/
	mv -f MamIRC/* MamIRC/.git "."
	rm -rf MamIRC/
fi

# Download a JAR file
if [ ! -e "nayuki-json-lib.jar" ]; then
	wget "http://www.nayuki.io/res/json-library-java/nayuki-json-lib.jar"
fi

# Download a ZIP file and extract
if [ ! -e "sqlite4java.jar" ]; then
	wget "https://d1.almworks.com/.files/sqlite4java/sqlite4java-392.zip"
	unzip sqlite4java-392.zip -d .
	mv sqlite4java-392/*.jar sqlite4java-392/*.so "."
	rm -rf "sqlite4java-392/" "sqlite4java-392.zip"
fi

# Delete all .class files in bin/
mkdir -p bin/
find bin/ -name '*.class' | xargs rm -f -v

# Compile the MamIRC connector and processor
echo "Compiling Java source code..."
javac -sourcepath "src/" -d "bin/" -cp "sqlite4java.jar:nayuki-json-lib.jar" "src/io/nayuki/mamirc/connector/MamircConnector.java" "src/io/nayuki/mamirc/processor/MamircProcessor.java"
