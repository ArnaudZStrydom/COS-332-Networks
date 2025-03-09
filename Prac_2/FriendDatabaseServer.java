import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.logging.*;
import java.util.stream.*;

public class FriendDatabaseServer {
    private static final int PORT = 8080;
    private static final String FILE_NAME = "friends.txt";
    private static final String ADMIN_PASSWORD = "admin123"; // Change this in production
    private static final Map<String, String> friends = new HashMap<>();
    private static final Set<String> activeUsers = ConcurrentHashMap.newKeySet(); // Active users set
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{7,15}");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z ]{1,30}$");

    private static final Logger logger = Logger.getLogger(FriendDatabaseServer.class.getName());

    public static void main(String[] args) {
        setupLogger();
        loadDatabase();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            logger.info("Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("New client connected: " + clientSocket.getInetAddress());
                threadPool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            logger.severe("Server error: " + e.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }

    private static void setupLogger() {
        try {
            FileHandler fileHandler = new FileHandler("server.log", true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
            logger.setUseParentHandlers(false);
        } catch (IOException e) {
            System.err.println("Failed to initialize logger: " + e.getMessage());
        }
    }

    private static synchronized void loadDatabase() {
        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_NAME))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    friends.put(parts[0].trim(), parts[1].trim());
                }
            }
            logger.info("Database loaded successfully.");
        } catch (IOException e) {
            logger.warning("No existing database found. Starting fresh.");
        }
    }

    private static synchronized void saveDatabase() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(FILE_NAME))) {
            for (Map.Entry<String, String> entry : friends.entrySet()) {
                writer.println(entry.getKey() + "," + entry.getValue());
            }
            logger.info("Database saved successfully.");
        } catch (IOException e) {
            logger.severe("Error saving database: " + e.getMessage());
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                // Clear screen and display welcome message
                out.print(AnsiUtils.clearScreen());
                out.print(AnsiUtils.moveCursor(1, 1));
                out.println(AnsiUtils.colorize("=== Friend Database Server ===", AnsiUtils.Color.BLUE));
                out.println("Enter Admin Password:");

                String password = in.readLine();
                if (!ADMIN_PASSWORD.equals(password)) {
                    logger.warning("Unauthorized access attempt from " + socket.getInetAddress());
                    out.println(AnsiUtils.colorize("Access Denied. Disconnecting.", AnsiUtils.Color.RED));
                    socket.close();
                    return;
                }

                logger.info("Client authenticated: " + socket.getInetAddress());
                out.println(AnsiUtils.colorize("Access Granted. Welcome!", AnsiUtils.Color.GREEN));
                displayHelp(out);

                // Track active user
                activeUsers.add(socket.getInetAddress().toString());
                logger.info("Active users: " + activeUsers.size());

                String input;
                while ((input = in.readLine()) != null) {
                    input = input.trim();
                    if (input.isEmpty()) continue;

                    String[] parts = input.split("\\s+", 3);
                    if (parts.length == 0) continue;

                    String command = parts[0].toLowerCase();
                    logger.info("Command received from " + socket.getInetAddress() + ": " + input);

                    switch (command) {
                        case "add":
                            handleAddCommand(parts, in, out);
                            break;

                        case "search":
                            handleSearchCommand(parts, out);
                            break;

                        case "delete":
                            handleDeleteCommand(parts, out);
                            break;

                        case "list":
                            handleListCommand(out);
                            break;

                        case "usercount":
                            out.println("Active users: " + activeUsers.size());
                            logger.info("Active users query from: " + socket.getInetAddress());
                            break;

                        case "help":
                            displayHelp(out);
                            break;

                        case "exit":
                            out.println(AnsiUtils.colorize("Goodbye!", AnsiUtils.Color.BLUE));
                            activeUsers.remove(socket.getInetAddress().toString());
                            logger.info("Client disconnected: " + socket.getInetAddress());
                            socket.close();
                            return;

                        default:
                            out.println(AnsiUtils.colorize("Unknown command. Type 'help' for a list of commands.", AnsiUtils.Color.RED));
                            break;
                    }
                }
            } catch (IOException e) {
                logger.severe("Client error: " + e.getMessage());
            }
        }

        private void handleAddCommand(String[] parts, BufferedReader in, PrintWriter out) throws IOException {
            if (parts.length == 3) {
                String name = parts[1].trim();
                String number = parts[2].trim();

                if (!NAME_PATTERN.matcher(name).matches()) {
                    out.println(AnsiUtils.colorize("Invalid name. Use only letters and spaces (1-30 characters).", AnsiUtils.Color.RED));
                    return;
                }
                if (!PHONE_PATTERN.matcher(number).matches()) {
                    out.println(AnsiUtils.colorize("Invalid phone number. Use 7-15 digits only.", AnsiUtils.Color.RED));
                    return;
                }

                synchronized (friends) {
                    if (friends.containsKey(name)) {
                        out.println(AnsiUtils.colorize("This name already exists. Overwrite? (yes/no)", AnsiUtils.Color.YELLOW));
                        String response = in.readLine().trim().toLowerCase();
                        if (!response.equals("yes")) {
                            out.println(AnsiUtils.colorize("Addition cancelled.", AnsiUtils.Color.YELLOW));
                            return;
                        }
                    }
                    friends.put(name, number);
                    saveDatabase();
                    out.println(AnsiUtils.colorize("Friend added successfully.", AnsiUtils.Color.GREEN));
                    logger.info("Friend added: " + name);
                }
            } else {
                out.println(AnsiUtils.colorize("Usage: add [name] [number]", AnsiUtils.Color.RED));
            }
        }

        private void handleSearchCommand(String[] parts, PrintWriter out) {
            if (parts.length == 2) {
                String query = parts[1].trim().toLowerCase();
                synchronized (friends) {
                    List<String> matches = friends.entrySet().stream()
                        .filter(entry -> entry.getKey().toLowerCase().contains(query))
                        .map(entry -> entry.getKey() + " - " + entry.getValue())
                        .collect(Collectors.toList());
                    if (matches.isEmpty()) {
                        out.println(AnsiUtils.colorize("No matches found.", AnsiUtils.Color.YELLOW));
                    } else {
                        out.println(AnsiUtils.colorize("Search Results:", AnsiUtils.Color.BLUE));
                        matches.forEach(out::println);
                    }
                }
            } else {
                out.println(AnsiUtils.colorize("Usage: search [name]", AnsiUtils.Color.RED));
            }
        }

        private void handleDeleteCommand(String[] parts, PrintWriter out) {
            if (parts.length == 2) {
                String name = parts[1].trim();
                synchronized (friends) {
                    if (friends.remove(name) != null) {
                        saveDatabase();
                        out.println(AnsiUtils.colorize("Friend deleted.", AnsiUtils.Color.GREEN));
                        logger.info("Friend deleted: " + name);
                    } else {
                        out.println(AnsiUtils.colorize("Friend not found.", AnsiUtils.Color.RED));
                    }
                }
            } else {
                out.println(AnsiUtils.colorize("Usage: delete [name]", AnsiUtils.Color.RED));
            }
        }

        private void handleListCommand(PrintWriter out) {
            synchronized (friends) {
                if (friends.isEmpty()) {
                    out.println(AnsiUtils.colorize("No friends in the database.", AnsiUtils.Color.YELLOW));
                } else {
                    out.println(AnsiUtils.colorize("Friend List:", AnsiUtils.Color.BLUE));
                    for (Map.Entry<String, String> entry : friends.entrySet()) {
                        out.println(entry.getKey() + " - " + entry.getValue());
                    }
                }
            }
        }

        private void displayHelp(PrintWriter out) {
            out.println(AnsiUtils.colorize("Available Commands:", AnsiUtils.Color.BLUE));
            out.println("  add [name] [number] - Add a new friend");
            out.println("  search [name]      - Search for a friend");
            out.println("  delete [name]      - Delete a friend");
            out.println("  list               - List all friends");
            out.println("  usercount          - Show number of active users");
            out.println("  help               - Display this help message");
            out.println("  exit               - Disconnect from the server");
        }
    }

    // Utility class for ANSI escape sequences
    public static class AnsiUtils {
        public enum Color {
            RED("\u001B[31m"),
            GREEN("\u001B[32m"),
            YELLOW("\u001B[33m"),
            BLUE("\u001B[34m"),
            RESET("\u001B[0m");

            private final String code;

            Color(String code) {
                this.code = code;
            }

            @Override
            public String toString() {
                return code;
            }
        }

        public static String clearScreen() {
            return "\u001B[2J\u001B[H";
        }

        public static String moveCursor(int row, int col) {
            return String.format("\u001B[%d;%dH", row, col);
        }

        public static String colorize(String text, Color color) {
            return color + text + Color.RESET;
        }
    }
}