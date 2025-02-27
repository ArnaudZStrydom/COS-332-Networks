import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import java.util.logging.*;

public class FriendDatabaseServer {
    private static final int PORT = 8080;
    private static final String FILE_NAME = "friends.txt";
    private static final String ADMIN_PASSWORD = "admin123"; // Change this in production
    private static final Map<String, String> friends = new HashMap<>();

    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{7,15}");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z ]{1,30}$");

    private static final Logger logger = Logger.getLogger(FriendDatabaseServer.class.getName());
    private static final AtomicInteger activeUsers = new AtomicInteger(0);  // Counter for active users

    public static void main(String[] args) {
        setupLogger();
        loadDatabase();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            logger.info("Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("New client connected: " + clientSocket.getInetAddress());
                activeUsers.incrementAndGet();  // Increment active users count
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

                out.println("Enter Admin Password:");
                String password = in.readLine();
                
                if (!ADMIN_PASSWORD.equals(password)) {
                    logger.warning("Unauthorized access attempt from " + socket.getInetAddress());
                    out.println("Access Denied. Disconnecting.");
                    socket.close();
                    return;
                }
                logger.info("Client authenticated: " + socket.getInetAddress());
                out.println("Access Granted. Welcome!");
                out.println("Commands: add [name] [number], search [name|number], delete [name], edit [name], list, usercount, exit");

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
                            if (parts.length == 3) {
                                String name = parts[1].trim();
                                String number = parts[2].trim();

                                if (!NAME_PATTERN.matcher(name).matches()) {
                                    out.println("Invalid name. Use only letters and spaces (1-30 characters).");
                                    break;
                                }
                                if (!PHONE_PATTERN.matcher(number).matches()) {
                                    out.println("Invalid phone number. Use 7-15 digits only.");
                                    break;
                                }

                                synchronized (friends) {
                                    if (friends.containsKey(name)) {
                                        out.println("This name already exists. Overwrite? (yes/no)");
                                        String response = in.readLine().trim().toLowerCase();
                                        if (!response.equals("yes")) {
                                            out.println("Addition cancelled.");
                                            break;
                                        }
                                    }
                                    friends.put(name, number);
                                    saveDatabase();
                                    out.println("Friend added successfully.");
                                    logger.info("Friend added: " + name);
                                }
                            } else {
                                out.println("Usage: add [name] [number]");
                            }
                            break;

                        case "search":
                            if (parts.length == 2) {
                                String query = parts[1].trim();
                                synchronized (friends) {
                                    if (NAME_PATTERN.matcher(query).matches()) {
                                        // Search by name
                                        String number = friends.get(query);
                                        out.println(number != null ? "Found: " + query + " - " + number : "Friend not found.");
                                        logger.info("Search query by name: " + query);
                                    } else if (PHONE_PATTERN.matcher(query).matches()) {
                                        // Search by phone number
                                        String foundName = null;
                                        for (Map.Entry<String, String> entry : friends.entrySet()) {
                                            if (entry.getValue().equals(query)) {
                                                foundName = entry.getKey();
                                                break;
                                            }
                                        }
                                        out.println(foundName != null ? "Found: " + foundName + " - " + query : "Friend not found.");
                                        logger.info("Search query by number: " + query);
                                    } else {
                                        out.println("Invalid query. Use a valid name or phone number.");
                                    }
                                }
                            } else {
                                out.println("Usage: search [name|number]");
                            }
                            break;

                        case "edit":
                            if (parts.length == 2) {
                                String name = parts[1].trim();

                                synchronized (friends) {
                                    if (friends.containsKey(name)) {
                                        out.println("Friend found: " + name + " - " + friends.get(name));
                                        out.println("What would you like to edit? [name|number|both]");
                                        String choice = in.readLine().trim().toLowerCase();
                                        if (choice.equals("name")) {
                                            out.println("Enter new name:");
                                            String newName = in.readLine().trim();
                                            friends.put(newName, friends.remove(name));
                                            saveDatabase();
                                            out.println("Name updated successfully.");
                                        } else if (choice.equals("number")) {
                                            out.println("Enter new phone number:");
                                            String newNumber = in.readLine().trim();
                                            if (!PHONE_PATTERN.matcher(newNumber).matches()) {
                                                out.println("Invalid phone number format.");
                                            } else {
                                                friends.put(name, newNumber);
                                                saveDatabase();
                                                out.println("Phone number updated successfully.");
                                            }
                                        } else if (choice.equals("both")) {
                                            out.println("Enter new name:");
                                            String newName = in.readLine().trim();
                                            out.println("Enter new phone number:");
                                            String newNumber = in.readLine().trim();
                                            if (!PHONE_PATTERN.matcher(newNumber).matches()) {
                                                out.println("Invalid phone number format.");
                                            } else {
                                                friends.put(newName, newNumber);
                                                friends.remove(name);
                                                saveDatabase();
                                                out.println("Name and phone number updated successfully.");
                                            }
                                        } else {
                                            out.println("Invalid choice. No changes made.");
                                        }
                                        logger.info("Friend updated: " + name);
                                    } else {
                                        out.println("Friend not found.");
                                    }
                                }
                            } else {
                                out.println("Usage: edit [name]");
                            }
                            break;

                        case "delete":
                            if (parts.length == 2) {
                                String name = parts[1].trim();
                                synchronized (friends) {
                                    if (friends.remove(name) != null) {
                                        saveDatabase();
                                        out.println("Friend deleted.");
                                        logger.info("Friend deleted: " + name);
                                    } else {
                                        out.println("Friend not found.");
                                    }
                                }
                            } else {
                                out.println("Usage: delete [name]");
                            }
                            break;

                        case "list":
                            synchronized (friends) {
                                if (friends.isEmpty()) {
                                    out.println("No friends in the database.");
                                } else {
                                    out.println("Friend List:");
                                    for (Map.Entry<String, String> entry : friends.entrySet()) {
                                        out.println(entry.getKey() + " - " + entry.getValue());
                                    }
                                }
                            }
                            break;

                        case "usercount":
                            out.println("Active users: " + activeUsers.get());
                            break;

                        case "exit":
                            out.println("Goodbye!");
                            logger.info("Client disconnected: " + socket.getInetAddress());
                            socket.close();
                            activeUsers.decrementAndGet();  // Decrement active users count
                            return;

                        default:
                            out.println("Unknown command.");
                            break;
                    }
                }
            } catch (IOException e) {
                logger.severe("Client error: " + e.getMessage());
            }
        }
    }
}
