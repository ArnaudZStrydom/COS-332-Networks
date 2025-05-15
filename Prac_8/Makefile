# Makefile for FTP Uploader application

# Compiler settings
JC = javac
JFLAGS = -g -d bin
JVM = java
CLASSPATH = bin

# Source and build directories
SRC_DIR = src
BIN_DIR = bin

# Source files
SOURCES = FTPMonitor.java

# Make sure the bin directory exists
$(shell mkdir -p $(BIN_DIR))

# Default target
all: compile

# Compile all Java files
compile:
	$(JC) $(JFLAGS) $(SOURCES)

# Run the application
run: compile
	$(JVM) -cp $(CLASSPATH) FTPMonitor

# Run with additional debug options
debug: compile
	$(JVM) -cp $(CLASSPATH) -Djavax.net.debug=all FTPMonitor

# Clean the build directory
clean:
	rm -rf $(BIN_DIR)/*

# Package the application into a runnable JAR
package: compile
	mkdir -p dist
	echo "Main-Class: FTPMonitor" > manifest.txt
	jar cvfm dist/FTPMonitor.jar manifest.txt -C $(BIN_DIR) .
	rm manifest.txt

# Run the packaged JAR
run-jar: package
	$(JVM) -jar dist/FTPMonitor.jar

# Help target to display available commands
help:
	@echo "Available targets:"
	@echo "  make all      - Compile the application"
	@echo "  make compile  - Compile the application"
	@echo "  make run      - Run the application"
	@echo "  make debug    - Run with network debugging enabled"
	@echo "  make clean    - Clean compiled classes"
	@echo "  make package  - Create executable JAR file"
	@echo "  make run-jar  - Run the packaged JAR file"

.PHONY: all compile run debug clean package run-jar help