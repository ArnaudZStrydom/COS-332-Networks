# Makefile for FileMonitorFTP

# Java compiler
JC = javac
# Java runtime
JR = java
# Compiler flags
JFLAGS = -g

# Source file and class name
SRC = FileMonitorFTP.java
CLASS = FileMonitorFTP
JAR_FILE = FileMonitorFTP.jar

# Default target
all: compile

# Compile the Java source file
compile:
	$(JC) $(JFLAGS) $(SRC)
	@echo "Compilation completed successfully."

# Run the program
run: compile
	$(JR) $(CLASS)

# Create a runnable JAR file
jar: compile
	@echo "Creating JAR file..."
	@echo "Main-Class: $(CLASS)" > manifest.txt
	jar cvfm $(JAR_FILE) manifest.txt *.class
	@rm manifest.txt
	@echo "JAR file created: $(JAR_FILE)"

# Run the program from the JAR file
run-jar: jar
	$(JR) -jar $(JAR_FILE)

# Clean up compiled files
clean:
	@echo "Cleaning up..."
	@rm -f *.class manifest.txt $(JAR_FILE)
	@echo "Cleanup completed."

# Show help information
help:
	@echo "Available targets:"
	@echo "  all       - Default target, same as 'compile'"
	@echo "  compile   - Compile the Java source code"
	@echo "  run       - Compile and run the program"
	@echo "  jar       - Create a runnable JAR file"
	@echo "  run-jar   - Run the program from the JAR file"
	@echo "  clean     - Remove compiled files and JAR"
	@echo "  help      - Display this help information"
	@echo "  md5       - Calculate MD5 digest of the class file"

# Calculate MD5 digest (as mentioned in the assignment requirements)
md5: compile
	@echo "Calculating MD5 digest of $(CLASS).class..."
	@if command -v md5sum > /dev/null; then \
		md5sum $(CLASS).class; \
	elif command -v md5 > /dev/null; then \
		md5 $(CLASS).class; \
	else \
		echo "No MD5 utility found. Please install md5sum or md5."; \
	fi

.PHONY: all compile run jar run-jar clean help md5