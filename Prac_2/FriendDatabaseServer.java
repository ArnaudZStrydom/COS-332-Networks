import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class FriendDatabaseServer {
    private static final int PORT = 8080;
    private static final String FILE_NAME = "friends.txt";
    private static final String CLEAR_SCREEN = "\033[2J\033[H";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    private static final ConcurrentHashMap<String, String> friends = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        loadDatabase();
        
        Runtime.getRuntime().addShutdownHook(new Thread(FriendDatabaseServer::saveDatabase));

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new ClientHandler(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
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
        } catch (IOException e) {
            System.out.println("No existing database found. Starting fresh.");
        }
    }

    private static synchronized void saveDatabase() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(FILE_NAME))) {
            for (var entry : friends.entrySet()) {
                writer.println(entry.getKey() + "," + entry.getValue());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ClientHandler extends Thread {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                out.print(CLEAR_SCREEN);
                out.println(GREEN + "Welcome to the Friend Database Server!" + RESET);
                out.println("Commands: add [name] [number], search [name], edit [name], delete [name], list, exit");

                String input;
                while ((input = in.readLine()) != null) {
                    String[] parts = input.trim().split(" ", 3);
                    if (parts.length == 0) continue;
                    
                    String command = parts[0].toLowerCase();
                    switch (command) {
                        case "add":
                            if (parts.length == 3) {
                                friends.put(parts[1], parts[2]);
                                saveDatabase();
                                out.println(GREEN + "Friend added successfully." + RESET);
                            } else {
                                out.println(RED + "Usage: add [name] [number]" + RESET);
                            }
                            break;

                        case "search":
                            if (parts.length == 2) {
                                String number = friends.get(parts[1]);
                                if (number != null) {
                                    out.println(GREEN + "Found: " + parts[1] + " - " + number + RESET);
                                } else {
                                    out.println(RED + "Friend not found." + RESET);
                                }
                            } else {
                                out.println(RED + "Usage: search [name]" + RESET);
                            }
                            break;

                        case "delete":
                            if (parts.length == 2) {
                                if (friends.remove(parts[1]) != null) {
                                    saveDatabase();
                                    out.println(GREEN + "Friend deleted." + RESET);
                                } else {
                                    out.println(RED + "Friend not found." + RESET);
                                }
                            } else {
                                out.println(RED + "Usage: delete [name]" + RESET);
                            }
                            break;

                        case "list":
                            if (friends.isEmpty()) {
                                out.println(RED + "No friends in the database." + RESET);
                            } else {
                                out.println(GREEN + "\nFriend List:\n" + RESET);
                                out.println(String.format("%-20s %s", "Name", "Phone Number"));
                                out.println("--------------------------------");
                                for (var entry : friends.entrySet()) {
                                    out.println(String.format("%-20s %s", entry.getKey(), entry.getValue()));
                                }
                            }
                            break;
                        
                            case "edit":
                            if (parts.length == 2) {
                                String name = parts[1];
                                if (friends.containsKey(name)) {
                                    String currentNumber = friends.get(name);
                                    out.println(GREEN + name + " found" + RESET);
                                    out.println("Please select: update name, update phone number, or both");
                                    String option = in.readLine().trim().toLowerCase();
                                    
                                    String newName = name;
                                    String newNumber = currentNumber;
                                    
                                    if (option.equals("update name") || option.equals("both")) {
                                        out.println("Enter new name:");
                                        newName = in.readLine().trim();
                                        friends.remove(name);  // Remove old name
                                    }
                                    
                                    if (option.equals("update phone number") || option.equals("both")) {
                                        out.println("Enter new phone number:");
                                        newNumber = in.readLine().trim();
                                    }
                                    
                                    friends.put(newName, newNumber);  // Add updated entry
                                    saveDatabase();
                                    out.println(GREEN + "Friend updated successfully." + RESET);
                                } else {
                                    out.println(RED + "Friend not found." + RESET);
                                }
                            } else {
                                out.println(RED + "Usage: edit [name]" + RESET);
                            }
                            break;

                        case "exit":
                            out.println(GREEN + "Goodbye!" + RESET);
                            socket.close();
                            return;

                        default:
                            out.println(RED + "Unknown command." + RESET);
                            break;
                    }
                }
            } catch (IOException e) {
                System.out.println("Client disconnected.");
            }
        }
    }
}
