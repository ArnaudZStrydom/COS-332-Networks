import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class PhonebookServer {
    private static final int PORT = 8080;
    private static Map<String, String> phonebook = new HashMap<>();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new FormHandler());
        server.setExecutor(null);
        System.out.println("Server started on http://localhost:" + PORT);
        server.start();
    }

    static class FormHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if ("POST".equalsIgnoreCase(method)) {
                handlePostRequest(exchange);
            } else {
                handleGetRequest(exchange);
            }
        }

        private void handleGetRequest(HttpExchange exchange) throws IOException {
            StringBuilder response = new StringBuilder();
            response.append("<html><body>");
            response.append("<h1>Phonebook</h1>");
            response.append("<form method='POST'>");
            response.append("Name: <input type='text' name='name'><br>");
            response.append("Number: <input type='text' name='number'><br>");
            response.append("<input type='submit' value='Add Contact'>");
            response.append("</form>");
            
            // Display phonebook entries
            response.append("<h2>Contacts</h2><ul>");
            for (Map.Entry<String, String> entry : phonebook.entrySet()) {
                response.append("<li>").append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append(" ")
                        .append("<a href='?delete=").append(entry.getKey()).append("'>Delete</a></li>");
            }
            response.append("</ul>");
            response.append("</body></html>");

            sendResponse(exchange, response.toString());
        }

        private void handlePostRequest(HttpExchange exchange) throws IOException {
            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), "UTF-8");
            BufferedReader br = new BufferedReader(isr);
            String formData = br.readLine();
            br.close();

            if (formData != null && formData.contains("=")) {
                String[] params = formData.split("&");
                String name = params[0].split("=")[1];
                String number = params[1].split("=")[1];

                phonebook.put(URLDecoder.decode(name, "UTF-8"), URLDecoder.decode(number, "UTF-8"));
            }

            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(302, -1);
        }

        private void sendResponse(HttpExchange exchange, String response) throws IOException {
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}
