JAVAC = javac
JAVA = java
JAR = jar

SRC = FriendDatabaseServer.java
CLASS = FriendDatabaseServer.class
JAR_FILE = FriendDatabaseServer.jar

.PHONY: all run clean

all: $(CLASS)

$(CLASS): $(SRC)
	$(JAVAC) $(SRC)

run: $(CLASS)
	$(JAVA) FriendDatabaseServer

jar: $(CLASS)
	$(JAR) cvfe $(JAR_FILE) FriendDatabaseServer *.class

clean:
	rm -f *.class $(JAR_FILE)
