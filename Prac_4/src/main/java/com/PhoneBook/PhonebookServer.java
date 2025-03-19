package com.PhoneBook;

import com.sun.net.httpserver.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PhonebookServer {
    private static final int HTTP_PORT = 8080;
    private static final int WEBSOCKET_PORT = 8081;
    private static final String LOG_FILE = "log.txt";
    private static final String UPLOAD_DIR = "uploads";
    private static final Map<String, Contact> phonebook = new ConcurrentHashMap<>(); // Key is now the contact's ID
    private static final Set<String> activeUsers = new HashSet<>();
    private static PhonebookWebSocketServer webSocketServer;

    public static void main(String[] args) throws IOException {
        // Create uploads directory if it doesn't exist
        Files.createDirectories(Paths.get(UPLOAD_DIR));

        // Start HTTP server
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/", new FormHandler());
        httpServer.createContext("/uploads/", new ImageHandler());
        httpServer.createContext("/search", new SearchHandler());
        httpServer.setExecutor(null);
        httpServer.start();
        log("HTTP server started on http://localhost:" + HTTP_PORT);

        // Start WebSocket server
        webSocketServer = new PhonebookWebSocketServer(WEBSOCKET_PORT);
        webSocketServer.start();
        log("WebSocket server started on ws://localhost:" + WEBSOCKET_PORT);
    }

    // Logging method
    private static void log(String message) {
        // Log to terminal
        System.out.println(message);

        // Log to file
        try (FileWriter writer = new FileWriter(LOG_FILE, true)) {
            writer.write(new Date() + " - " + message + "\n");
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }

    // Contact class
    static class Contact {
        String id; // Unique identifier for each contact
        String name;
        String surname;
        String number;
        String imageUrl;

        Contact(String name, String surname, String number, String imageUrl) {
            this.id = UUID.randomUUID().toString(); // Generate a unique ID
            this.name = name;
            this.surname = surname;
            this.number = number;
            this.imageUrl = imageUrl;
        }

        String toJSON() {
            return String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"surname\":\"%s\",\"number\":\"%s\",\"imageUrl\":\"/uploads/%s\"}",
                id, name, surname, number, imageUrl
            );
        }
    }

    // HTTP handler for the form
    static class FormHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String sessionId = getSessionId(exchange);
            activeUsers.add(sessionId);

            if ("POST".equalsIgnoreCase(method)) {
                handlePostRequest(exchange);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGetRequest(exchange);
            }

            // Clean up inactive users (optional)
            activeUsers.removeIf(session -> !isSessionActive(session));
        }

        private void handleGetRequest(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            String query = uri.getQuery();

            // Handle deletion if delete parameter is present
            if (query != null && query.contains("delete=")) {
                String idToDelete = query.split("=")[1];
                idToDelete = URLDecoder.decode(idToDelete, "UTF-8");
                Contact contact = phonebook.remove(idToDelete);
                if (contact != null) {
                    // Delete the associated image file
                    Files.deleteIfExists(Paths.get(UPLOAD_DIR, contact.imageUrl));
                    log("Contact deleted: " + contact.name);

                    // Notify WebSocket server
                    webSocketServer.broadcastChange("delete:" + idToDelete);
                }
                exchange.getResponseHeaders().set("Location", "/");
                exchange.sendResponseHeaders(302, -1); // Redirect to homepage
                return;
            }

            // Build HTML response
            StringBuilder response = new StringBuilder();
            response.append("<html><head>")
                    .append("<title>Phonebook</title>")
                    .append("<style>")
                    .append("body { font-family: Arial, sans-serif; background-color: #f4f4f4; margin: 0; padding: 20px; }")
                    .append("h1 { color: #333; }")
                    .append("form { background: #fff; padding: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0, 0, 0, 0.1); }")
                    .append("input[type='text'], input[type='file'] { width: 100%; padding: 10px; margin: 10px 0; border: 1px solid #ddd; border-radius: 5px; }")
                    .append("input[type='submit'] { background: #28a745; color: #fff; border: none; padding: 10px 20px; border-radius: 5px; cursor: pointer; }")
                    .append("ul { list-style: none; padding: 0; }")
                    .append("li { background: #fff; padding: 10px; margin: 10px 0; border-radius: 5px; box-shadow: 0 0 10px rgba(0, 0, 0, 0.1); }")
                    .append("img { max-width: 100px; border-radius: 5px; }")
                    .append(".logs { background: #fff; padding: 20px; margin-top: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0, 0, 0, 0.1); }")
                    .append(".delete-button { background: #dc3545; color: #fff; border: none; padding: 5px 10px; border-radius: 3px; cursor: pointer; font-size: 12px; }")
                    .append(".popup { display: none; position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); background: #fff; padding: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0, 0, 0, 0.1); z-index: 1000; max-height: 80vh; overflow-y: auto; }")
                    .append(".popup img { max-width: 100px; border-radius: 5px; }")
                    .append("</style>")
                    .append("<script>")
                    .append("const socket = new WebSocket('ws://localhost:8081');")
                    .append("socket.onmessage = function(event) {")
                    .append("  const data = JSON.parse(event.data);")
                    .append("  if (data.activeUsers !== undefined) {")
                    .append("    document.getElementById('activeUsers').innerText = 'Active Users: ' + data.activeUsers;")
                    .append("  }")
                    .append("  if (data.change) {")
                    .append("    const change = data.change;")
                    .append("    if (change.startsWith('add:')) {")
                    .append("      const contact = JSON.parse(change.substring(4));")
                    .append("      const li = document.createElement('li');")
                    .append("      li.setAttribute('data-id', contact.id);")
                    .append("      li.innerHTML = `<img src='${contact.imageUrl}' alt='Contact Image'><br>${contact.name} ${contact.surname}: ${contact.number} <form method='GET' action='/' style='display: inline;'><input type='hidden' name='delete' value='${contact.id}'><button type='submit' class='delete-button'>Delete</button></form>`;")
                    .append("      document.querySelector('ul').appendChild(li);")
                    .append("    } else if (change.startsWith('delete:')) {")
                    .append("      const id = change.substring(7);")
                    .append("      const li = document.querySelector(`li[data-id='${id}']`);")
                    .append("      if (li) li.remove();")
                    .append("    }")
                    .append("  }")
                    .append("};")
                    .append("function searchContact() {")
                    .append("  const searchTerm = document.getElementById('searchTerm').value;")
                    .append("  fetch('/search?term=' + encodeURIComponent(searchTerm))")
                    .append("    .then(response => response.json())")
                    .append("    .then(data => {")
                    .append("      const popupContent = document.getElementById('popupContent');")
                    .append("      popupContent.innerHTML = '';")
                    .append("      if (data.length > 0) {")
                    .append("        data.forEach(contact => {")
                    .append("          const div = document.createElement('div');")
                    .append("          div.innerHTML = `<img src='${contact.imageUrl}' alt='Contact Image'><br><p><strong>Name:</strong> ${contact.name}</p><p><strong>Surname:</strong> ${contact.surname}</p><p><strong>Number:</strong> ${contact.number}</p><hr>`;")
                    .append("          popupContent.appendChild(div);")
                    .append("        });")
                    .append("        document.getElementById('popup').style.display = 'block';")
                    .append("      } else {")
                    .append("        alert('No contacts found');")
                    .append("      }")
                    .append("    });")
                    .append("}")
                    .append("function closePopup() {")
                    .append("  document.getElementById('popup').style.display = 'none';")
                    .append("}")
                    .append("</script>")
                    .append("</head><body>");

            response.append("<h1>Phonebook</h1>")
                    .append("<form method='POST' enctype='multipart/form-data'>")
                    .append("Name: <input type='text' name='name'><br>")
                    .append("Surname: <input type='text' name='surname'><br>")
                    .append("Number: <input type='text' name='number'><br>")
                    .append("Image: <input type='file' name='image'><br>")
                    .append("<input type='submit' value='Add Contact'>")
                    .append("</form>");

            // Add search bar
            response.append("<h2>Search Contact</h2>")
                    .append("<input type='text' id='searchTerm' placeholder='Enter name or surname'>")
                    .append("<button onclick='searchContact()'>Search</button>");

            // Display active users
            response.append("<p id='activeUsers'>Active Users: ").append(activeUsers.size()).append("</p>");

            // Display phonebook entries
            response.append("<h2>Contacts</h2><ul>");
            for (Contact contact : phonebook.values()) {
                response.append("<li data-id='").append(contact.id).append("'>")
                        .append("<img src='/uploads/").append(contact.imageUrl).append("' alt='Contact Image'><br>")
                        .append(contact.name).append(" ").append(contact.surname).append(": ").append(contact.number).append(" ")
                        .append("<form method='GET' action='/' style='display: inline;'>")
                        .append("<input type='hidden' name='delete' value='").append(contact.id).append("'>")
                        .append("<button type='submit' class='delete-button'>Delete</button>")
                        .append("</form></li>");
            }
            response.append("</ul>");

            // Display logs in the UI
            response.append("<div class='logs'><h2>Logs</h2>");
            try (BufferedReader logReader = new BufferedReader(new FileReader(LOG_FILE))) {
                String logLine;
                while ((logLine = logReader.readLine()) != null) {
                    response.append(logLine).append("<br>");
                }
            } catch (IOException e) {
                response.append("Failed to read logs.");
            }
            response.append("</div>");

            // Popup for searched contact
            response.append("<div id='popup' class='popup'>")
                    .append("<h2>Contact Details</h2>")
                    .append("<div id='popupContent'></div>")
                    .append("<button onclick='closePopup()'>Close</button>")
                    .append("</div>")
                    .append("</body></html>");

            sendResponse(exchange, response.toString());
        }

        private void handlePostRequest(HttpExchange exchange) throws IOException {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                String boundary = "--" + contentType.split("=")[1]; // Extract boundary
                InputStream requestBody = exchange.getRequestBody();

                // Read the entire request body into a byte array
                byte[] requestData = requestBody.readAllBytes();

                // Split the request data into parts using the boundary
                byte[] boundaryBytes = boundary.getBytes(StandardCharsets.UTF_8);
                List<byte[]> parts = splitMultipartData(requestData, boundaryBytes);

                String name = null, surname = null, number = null, imageFilename = null;

                for (byte[] part : parts) {
                    // Convert the part to a string to check for form fields
                    String partStr = new String(part, StandardCharsets.UTF_8);

                    if (partStr.contains("name=\"name\"")) {
                        name = partStr.split("\r\n\r\n")[1].trim();
                    } else if (partStr.contains("name=\"surname\"")) {
                        surname = partStr.split("\r\n\r\n")[1].trim();
                    } else if (partStr.contains("name=\"number\"")) {
                        number = partStr.split("\r\n\r\n")[1].trim();
                    } else if (partStr.contains("name=\"image\"")) {
                        // Handle image upload
                        int headerEndIndex = indexOf(part, "\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                        if (headerEndIndex != -1) {
                            // Extract the image data (binary)
                            byte[] imageData = Arrays.copyOfRange(part, headerEndIndex + 4, part.length);

                            // Generate a unique filename
                            String fileExtension = ".jpg"; // Default extension
                            if (partStr.contains("Content-Type: image/png")) {
                                fileExtension = ".png";
                            } else if (partStr.contains("Content-Type: image/gif")) {
                                fileExtension = ".gif";
                            }
                            String filename = "image_" + System.currentTimeMillis() + fileExtension;
                            Path imagePath = Paths.get(UPLOAD_DIR, filename);

                            // Write the binary image data to the file
                            Files.write(imagePath, imageData);
                            imageFilename = filename;
                        }
                    }
                }

                if (name != null && surname != null && number != null && imageFilename != null) {
                    Contact contact = new Contact(name, surname, number, imageFilename);
                    phonebook.put(contact.id, contact); // Use the contact's ID as the key
                    log("Contact added: " + name + " " + surname + " - " + number);

                    // Notify WebSocket server
                    webSocketServer.broadcastChange("add:" + contact.toJSON());
                }
            }

            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(302, -1); // Redirect to homepage
        }

        private List<byte[]> splitMultipartData(byte[] data, byte[] boundary) {
            List<byte[]> parts = new ArrayList<>();
            int start = 0;
            while (true) {
                int end = indexOf(data, boundary, start);
                if (end == -1) break;
                if (start != 0) { // Skip the first boundary
                    parts.add(Arrays.copyOfRange(data, start, end));
                }
                start = end + boundary.length;
            }
            return parts;
        }

        private int indexOf(byte[] array, byte[] target) {
            return indexOf(array, target, 0);
        }

        private int indexOf(byte[] array, byte[] target, int start) {
            outer:
            for (int i = start; i < array.length - target.length + 1; i++) {
                for (int j = 0; j < target.length; j++) {
                    if (array[i + j] != target[j]) continue outer;
                }
                return i;
            }
            return -1;
        }

        private void sendResponse(HttpExchange exchange, String response) throws IOException {
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }

        private String getSessionId(HttpExchange exchange) {
            return exchange.getRemoteAddress() + ":" + exchange.getHttpContext().getPath();
        }

        private boolean isSessionActive(String sessionId) {
            return activeUsers.contains(sessionId);
        }
    }

    // HTTP handler for image uploads
    static class ImageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            Path imagePath = Paths.get(UPLOAD_DIR, path.substring(path.lastIndexOf('/') + 1));
            if (Files.exists(imagePath)) {
                byte[] imageBytes = Files.readAllBytes(imagePath);
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                exchange.sendResponseHeaders(200, imageBytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(imageBytes);
                os.close();
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    // HTTP handler for search endpoint
    static class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("term=")) {
                exchange.sendResponseHeaders(400, -1); // Bad request
                return;
            }

            String searchTerm = query.split("=")[1];
            searchTerm = URLDecoder.decode(searchTerm, "UTF-8");

            List<Contact> matchingContacts = new ArrayList<>();
            for (Contact contact : phonebook.values()) {
                if (contact.name.equalsIgnoreCase(searchTerm) ||
                    contact.surname.equalsIgnoreCase(searchTerm) ||
                    contact.number.equalsIgnoreCase(searchTerm)) {
                    matchingContacts.add(contact);
                }
            }

            String response;
            if (!matchingContacts.isEmpty()) {
                response = "[";
                for (Contact contact : matchingContacts) {
                    response += contact.toJSON() + ",";
                }
                response = response.substring(0, response.length() - 1) + "]"; // Remove trailing comma
            } else {
                response = "[]";
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    // WebSocket server
    static class PhonebookWebSocketServer extends WebSocketServer {
        private static final Set<WebSocket> activeSockets = new HashSet<>();

        public PhonebookWebSocketServer(int port) {
            super(new InetSocketAddress(port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            activeSockets.add(conn);
            broadcastActiveUsers();
            System.out.println("New connection: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            activeSockets.remove(conn);
            broadcastActiveUsers();
            System.out.println("Closed connection: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // Handle incoming messages (if needed)
            System.out.println("Received message: " + message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            ex.printStackTrace();
        }

        @Override
        public void onStart() {
            System.out.println("WebSocket server started!");
        }

        public void broadcastActiveUsers() {
            String message = "{\"activeUsers\":" + activeSockets.size() + "}";
            for (WebSocket socket : activeSockets) {
                socket.send(message);
            }
        }

        public void broadcastChange(String change) {
            String message = "{\"change\":\"" + change + "\"}";
            for (WebSocket socket : activeSockets) {
                socket.send(message);
            }
        }
    }
}