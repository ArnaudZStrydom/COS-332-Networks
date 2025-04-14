# Makefile for AlarmSystem

# Java compiler
JC = javac

# Java runtime
JAVA = java

# Source files
SOURCES = AlarmSystem.java

# Class files
CLASSES = $(SOURCES:.java=.class)

# Default target
all: $(CLASSES)

# Compile Java files
%.class: %.java
	$(JC) $<

# Run the AlarmSystem
run: $(CLASSES)
	$(JAVA) AlarmSystem

# Clean generated files
clean:
	rm -f *.class

# Help target
help:
	@echo "Available targets:"
	@echo "  all    - Compile the AlarmSystem program (default)"
	@echo "  run    - Run the AlarmSystem program"
	@echo "  clean  - Remove compiled class files"
	@echo "  help   - Display this help message"

.PHONY: all run clean help