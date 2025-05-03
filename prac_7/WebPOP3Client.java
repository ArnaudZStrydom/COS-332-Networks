import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class WebPOP3Client {
    private SSLSocket pop3Socket;
    private BufferedReader pop3Reader;
    private BufferedWriter pop3Writer;
    private boolean pop3Connected = false;
    private ServerSocket webServer;
    private ExecutorService threadPool;
    private volatile boolean running = true;
    private List<EmailMessage> allMessages = new ArrayList<>();
    private boolean fullListLoaded = false;
    private final Object pop3Lock = new Object();
    private long lastRefreshTime = 0;
    private static final long REFRESH_INTERVAL_MS = 30000; // 30 seconds

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java WebPOP3Client <email> <password> [pop3server] [pop3port] [webport]");
            System.out.println("Example: java WebPOP3Client user@gmail.com password pop.gmail.com 995 8080");
            return;
        }

        String email = args[0];
        String password = args[1];
        String pop3Server = args.length > 2 ? args[2] : "pop.gmail.com";
        int pop3Port = args.length > 3 ? Integer.parseInt(args[3]) : 995;
        int webPort = args.length > 4 ? Integer.parseInt(args[4]) : 8080;

        WebPOP3Client client = new WebPOP3Client();
        
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))){
            client.connectPOP3(pop3Server, pop3Port);
            client.loginPOP3(email, password);
            client.startWebServer(webPort);
            System.out.println("Web interface running at http://localhost:" + webPort);
            System.out.println("Type 'quit' to shutdown");
            // Keep running until shutdown
            while (client.running) {
                Thread.sleep(1000);
                if ("quit".equalsIgnoreCase(br.readLine())) {
                    client.stop();
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            //client.stop();
        }
    }

    /* ================= POP3 Client Methods ================= */
    private void connectPOP3(String server, int port) throws IOException {
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory)SSLSocketFactory.getDefault();
        pop3Socket = (SSLSocket)sslSocketFactory.createSocket(server, port);
        pop3Socket.setSoTimeout(30000); // 30 seconds timeout
        pop3Reader = new BufferedReader(new InputStreamReader(pop3Socket.getInputStream()));
        pop3Writer = new BufferedWriter(new OutputStreamWriter(pop3Socket.getOutputStream()));
        
        String response = readPOP3Response();
        if (!response.startsWith("+OK")) {
            throw new IOException("POP3 connection failed: " + response);
        }
        
        pop3Connected = true;
        System.out.println("Connected to POP3 server");
    }

    private void loginPOP3(String email, String password) throws IOException {
        if (!pop3Connected) throw new IOException("Not connected to POP3 server");
        
        sendPOP3Command("USER " + email);
        String userResponse = readPOP3Response();
        if (!userResponse.startsWith("+OK")) {
            throw new IOException("POP3 login failed (user): " + userResponse);
        }
        
        sendPOP3Command("PASS " + password);
        String passResponse = readPOP3Response();
        if (!passResponse.startsWith("+OK")) {
            throw new IOException("POP3 login failed (pass): " + passResponse);
        }
        System.out.println("Successfully authenticated with POP3 server");
    }

    private List<EmailMessage> getMessageList() throws IOException {
        synchronized (pop3Lock) {
            if (!pop3Connected) throw new IOException("Not connected to POP3 server");
            
            sendPOP3Command("LIST");
            List<String> response = readPOP3MultilineResponse();
            
            List<EmailMessage> messages = new ArrayList<>();
            for (String line : response) {
                if (line.startsWith("+OK") || line.equals(".") || line.isEmpty()) continue;
                
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    EmailMessage msg = new EmailMessage();
                    msg.id = Integer.parseInt(parts[0]);
                    msg.size = Integer.parseInt(parts[1]);
                    messages.add(msg);
                }
            }
            return messages;
        }
    }

    private void loadFullMessageList() throws IOException {
        if (fullListLoaded) return;
        
        allMessages = getMessageList();
        fullListLoaded = true;
    }

    private void getMessageHeaders(EmailMessage message) throws IOException {
        synchronized (pop3Lock) {
            if (!pop3Connected) throw new IOException("Not connected to POP3 server");
            
            sendPOP3Command("TOP " + message.id + " 20");
            List<String> response = readPOP3MultilineResponse();
            
            for (String line : response) {
                if (line.equals(".") || line.isEmpty()) continue;
                
                String lowerLine = line.toLowerCase();
                if (lowerLine.startsWith("from:")) {
                    message.from = line.substring(5).trim();
                } else if (lowerLine.startsWith("subject:")) {
                    message.subject = line.substring(8).trim();
                } else if (lowerLine.startsWith("date:")) {
                    message.date = line.substring(5).trim();
                }
            }
        }
    }

    private boolean deleteMessage(int msgId) throws IOException {
        synchronized (pop3Lock) {
            if (!pop3Connected) throw new IOException("Not connected to POP3 server");
            
            sendPOP3Command("DELE " + msgId);
            String response = readPOP3Response();
            return response.startsWith("+OK");
        }
    }

    private void sendPOP3Command(String command) throws IOException {
        pop3Writer.write(command + "\r\n");
        pop3Writer.flush();
    }

    private String readPOP3Response() throws IOException {
        String response = pop3Reader.readLine();
        if (response == null) {
            throw new IOException("POP3 connection closed by server");
        }
        return response;
    }

    private List<String> readPOP3MultilineResponse() throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = pop3Reader.readLine()) != null) {
            if (line.equals(".")) break;
            lines.add(line);
        }
        return lines;
    }

    /* ================= Web Server Methods ================= */
    private void startWebServer(int port) throws IOException {
        webServer = new ServerSocket(port);
        threadPool = Executors.newFixedThreadPool(10);
        
        threadPool.execute(() -> {
            while (running) {
                try {
                    Socket clientSocket = webServer.accept();
                    clientSocket.setSoTimeout(30000); // 30 seconds timeout
                    threadPool.execute(() -> handleWebRequest(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Web server error: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private void handleWebRequest(Socket clientSocket) {
        try (InputStream input = clientSocket.getInputStream();
             OutputStream output = clientSocket.getOutputStream()) {
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            
            // Read HTTP request line
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                send400Response(output, "Empty request");
                return;
            }
            
            // Parse request line
            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                send400Response(output, "Malformed request line");
                return;
            }
            
            String method = requestParts[0];
            String fullPath = requestParts[1];
            String path = fullPath.split("\\?")[0];
            
            // Read and discard headers
            String headerLine;
            while ((headerLine = reader.readLine()) != null) {
                if (headerLine.isEmpty()) {
                    break; // End of headers
                }
            }
            
            // Handle routes
            if (path.equals("/") || path.equals("/index.html")) {
                try {
                    Map<String, String> params = parseQuery(fullPath.contains("?") ? 
                            fullPath.substring(fullPath.indexOf("?") + 1) : "");
                    
                    int page = Integer.parseInt(params.getOrDefault("page", "1"));
                    int pageSize = Integer.parseInt(params.getOrDefault("pageSize", "10"));
                    boolean listAll = Boolean.parseBoolean(params.getOrDefault("listAll", "false"));
                    
                    sendHtmlResponse(output, buildInboxPage(page, pageSize, listAll));
                } catch (IOException e) {
                    send500Response(output, "Error generating inbox page: " + e.getMessage());
                    e.printStackTrace();
                } catch (NumberFormatException e) {
                    send400Response(output, "Invalid page or pageSize parameter");
                }
            } else if (path.equals("/delete")) {
                handleDeleteRequest(fullPath, output);
            } else if (path.equals("/load-full")) {
                try {
                    loadFullMessageList();
                    sendRedirectResponse(output, "/");
                } catch (IOException e) {
                    send500Response(output, "Error loading full message list: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                send404Response(output);
            }
        } catch (Exception e) {
            System.err.println("Request handling error: " + e.getMessage());
            e.printStackTrace();
            try {
                send500Response(clientSocket.getOutputStream(), "Internal server error");
            } catch (IOException ex) {
                System.err.println("Failed to send error response: " + ex.getMessage());
            }
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }

    private void handleDeleteRequest(String fullPath, OutputStream output) throws IOException {
        Map<String, String> params = parseQuery(fullPath.contains("?") ? 
                fullPath.substring(fullPath.indexOf("?") + 1) : "");
        
        String msgIdsParam = params.getOrDefault("msgid", "");
        if (!msgIdsParam.isEmpty()) {
            String[] msgIds = msgIdsParam.split(",");
            for (String idStr : msgIds) {
                try {
                    int msgId = Integer.parseInt(idStr.trim());
                    if (deleteMessage(msgId)) {
        
                        System.out.println("Deleted message ID: " + msgId);
                        allMessages.removeIf(msg -> msg.id == msgId);
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Invalid message ID format: " + idStr);
                } catch (IOException e) {
                    System.err.println("Error deleting message " + idStr + ": " + e.getMessage());
                }
            }
        }
        
        sendRedirectResponse(output, "/");
    }

    private String buildInboxPage(int currentPage, int pageSize, boolean listAll) throws IOException {
        // Refresh messages if it's been more than 30 seconds since last refresh
        if (System.currentTimeMillis() - lastRefreshTime > REFRESH_INTERVAL_MS) {
            allMessages = getMessageList();
            fullListLoaded = true;
            lastRefreshTime = System.currentTimeMillis();
        }

        if (listAll) {
            loadFullMessageList();
        }
        
        List<EmailMessage> messagesToDisplay;
        if (fullListLoaded) {
            int startIdx = (currentPage - 1) * pageSize;
            int endIdx = Math.min(startIdx + pageSize, allMessages.size());
            
            if (startIdx >= allMessages.size()) {
                startIdx = 0;
                currentPage = 1;
            }
            
            messagesToDisplay = allMessages.subList(startIdx, endIdx);
        } else {
            messagesToDisplay = getMessageList();
            if (messagesToDisplay.size() > pageSize) {
                messagesToDisplay = messagesToDisplay.subList(0, pageSize);
            }
        }
        
        for (EmailMessage msg : messagesToDisplay) {
            getMessageHeaders(msg);
        }
    
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
            .append("<html>\n")
            .append("<head>\n")
            .append("    <title>POP3 Email Client</title>\n")
            .append("    <meta charset=\"UTF-8\">\n")
            .append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("    <style>\n")
            .append("        body {\n")
            .append("            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;\n")
            .append("            line-height: 1.6;\n")
            .append("            color: #333;\n")
            .append("            max-width: 1200px;\n")
            .append("            margin: 0 auto;\n")
            .append("            padding: 20px;\n")
            .append("            background-color: #f9f9f9;\n")
            .append("        }\n")
            .append("        h1 {\n")
            .append("            color: #2c3e50;\n")
            .append("            border-bottom: 2px solid #3498db;\n")
            .append("            padding-bottom: 10px;\n")
            .append("            margin-bottom: 20px;\n")
            .append("        }\n")
            .append("        .header {\n")
            .append("            display: flex;\n")
            .append("            justify-content: space-between;\n")
            .append("            align-items: center;\n")
            .append("            margin-bottom: 20px;\n")
            .append("            flex-wrap: wrap;\n")
            .append("            gap: 10px;\n")
            .append("        }\n")
            .append("        .btn {\n")
            .append("            display: inline-block;\n")
            .append("            background-color: #3498db;\n")
            .append("            color: white;\n")
            .append("            padding: 8px 16px;\n")
            .append("            text-decoration: none;\n")
            .append("            border-radius: 4px;\n")
            .append("            border: none;\n")
            .append("            cursor: pointer;\n")
            .append("            font-size: 14px;\n")
            .append("            transition: all 0.3s;\n")
            .append("            box-shadow: 0 2px 5px rgba(0,0,0,0.1);\n")
            .append("        }\n")
            .append("        .btn:hover {\n")
            .append("            background-color: #2980b9;\n")
            .append("            transform: translateY(-1px);\n")
            .append("            box-shadow: 0 4px 8px rgba(0,0,0,0.15);\n")
            .append("        }\n")
            .append("        .btn:active {\n")
            .append("            transform: translateY(0);\n")
            .append("        }\n")
            .append("        .btn-danger {\n")
            .append("            background-color: #e74c3c;\n")
            .append("        }\n")
            .append("        .btn-danger:hover {\n")
            .append("            background-color: #c0392b;\n")
            .append("        }\n")
            .append("        .btn-success {\n")
            .append("            background-color: #2ecc71;\n")
            .append("        }\n")
            .append("        .btn-success:hover {\n")
            .append("            background-color: #27ae60;\n")
            .append("        }\n")
            .append("        table {\n")
            .append("            width: 100%;\n")
            .append("            border-collapse: collapse;\n")
            .append("            margin-bottom: 20px;\n")
            .append("            box-shadow: 0 2px 10px rgba(0,0,0,0.1);\n")
            .append("            background-color: white;\n")
            .append("            border-radius: 8px;\n")
            .append("            overflow: hidden;\n")
            .append("        }\n")
            .append("        th, td {\n")
            .append("            padding: 12px 15px;\n")
            .append("            text-align: left;\n")
            .append("            border-bottom: 1px solid #e0e0e0;\n")
            .append("        }\n")
            .append("        th {\n")
            .append("            background-color: #3498db;\n")
            .append("            color: white;\n")
            .append("            font-weight: 500;\n")
            .append("            text-transform: uppercase;\n")
            .append("            font-size: 0.85em;\n")
            .append("            letter-spacing: 0.5px;\n")
            .append("        }\n")
            .append("        tr:hover {\n")
            .append("            background-color: #f5f9ff;\n")
            .append("        }\n")
            .append("        .checkbox-cell {\n")
            .append("            width: 40px;\n")
            .append("            text-align: center;\n")
            .append("        }\n")
            .append("        .size-cell {\n")
            .append("            width: 100px;\n")
            .append("        }\n")
            .append("        .date-cell {\n")
            .append("            width: 180px;\n")
            .append("        }\n")
            .append("        .actions {\n")
            .append("            margin-top: 20px;\n")
            .append("            display: flex;\n")
            .append("            gap: 10px;\n")
            .append("            flex-wrap: wrap;\n")
            .append("        }\n")
            .append("        .message-count {\n")
            .append("            margin-bottom: 20px;\n")
            .append("            font-style: italic;\n")
            .append("            color: #7f8c8d;\n")
            .append("            font-size: 0.9em;\n")
            .append("        }\n")
            .append("        .footer {\n")
            .append("            margin-top: 30px;\n")
            .append("            text-align: center;\n")
            .append("            color: #7f8c8d;\n")
            .append("            font-size: 12px;\n")
            .append("            padding-top: 20px;\n")
            .append("            border-top: 1px solid #eee;\n")
            .append("        }\n")
            .append("        .pagination {\n")
            .append("            display: flex;\n")
            .append("            justify-content: center;\n")
            .append("            gap: 5px;\n")
            .append("            margin-top: 20px;\n")
            .append("            flex-wrap: wrap;\n")
            .append("        }\n")
            .append("        .pagination a, .pagination span {\n")
            .append("            padding: 8px 12px;\n")
            .append("            border: 1px solid #ddd;\n")
            .append("            text-decoration: none;\n")
            .append("            color: #3498db;\n")
            .append("            border-radius: 4px;\n")
            .append("            transition: all 0.3s;\n")
            .append("        }\n")
            .append("        .pagination a:hover {\n")
            .append("            background-color: #f1f9ff;\n")
            .append("            border-color: #3498db;\n")
            .append("        }\n")
            .append("        .pagination .current {\n")
            .append("            background-color: #3498db;\n")
            .append("            color: white;\n")
            .append("            border-color: #3498db;\n")
            .append("        }\n")
            .append("        input[type=\"checkbox\"] {\n")
            .append("            transform: scale(1.2);\n")
            .append("            cursor: pointer;\n")
            .append("        }\n")
            .append("        @media (max-width: 768px) {\n")
            .append("            th, td {\n")
            .append("                padding: 8px 10px;\n")
            .append("                font-size: 0.9em;\n")
            .append("            }\n")
            .append("            .header {\n")
            .append("                flex-direction: column;\n")
            .append("                align-items: flex-start;\n")
            .append("            }\n")
            .append("        }\n")
            .append("    </style>\n")
            .append("</head>\n")
            .append("<body>\n")
            .append("    <div class=\"header\">\n")
            .append("        <h1>Email Inbox</h1>\n")
            .append("        <div>\n")
            .append("            <a href=\"/\" class=\"btn\">Refresh</a>\n");
            
        if (!fullListLoaded) {
            html.append("            <a href=\"/load-full\" class=\"btn btn-success\">List All</a>\n");
        }
            
        html.append("        </div>\n")
            .append("    </div>\n")
            .append("    <div class=\"message-count\">")
            .append(messagesToDisplay.size())
            .append(" messages")
            .append(fullListLoaded ? " (loaded " + allMessages.size() + " total)" : "")
            .append("</div>\n")
            .append("    <form action=\"/delete\" method=\"get\">\n")
            .append("        <table>\n")
            .append("            <thead>\n")
            .append("                <tr>\n")
            .append("                    <th class=\"checkbox-cell\">Select</th>\n")
            .append("                    <th>From</th>\n")
            .append("                    <th>Subject</th>\n")
            .append("                    <th class=\"size-cell\">Size</th>\n")
            .append("                    <th class=\"date-cell\">Date</th>\n")
            .append("                </tr>\n")
            .append("            </thead>\n")
            .append("            <tbody>\n");
    
        for (EmailMessage msg : messagesToDisplay) {
            html.append("                <tr>\n")
                .append("                    <td class=\"checkbox-cell\">\n")
                .append("                        <input type=\"checkbox\" name=\"msgid\" value=\"").append(msg.id).append("\">\n")
                .append("                    </td>\n")
                .append("                    <td>").append(escapeHtml(truncate(msg.from, 30))).append("</td>\n")
                .append("                    <td>").append(escapeHtml(truncate(msg.subject, 50))).append("</td>\n")
                .append("                    <td>").append(formatSize(msg.size)).append("</td>\n")
                .append("                    <td>").append(escapeHtml(msg.date != null ? truncate(msg.date, 20) : "Unknown")).append("</td>\n")
                .append("                </tr>\n");
        }
    
        html.append("            </tbody>\n")
            .append("        </table>\n")
            .append("        <div class=\"actions\">\n")
            .append("            <button type=\"submit\" class=\"btn btn-danger\">Delete Selected</button>\n")
            .append("        </div>\n");
            
        if (fullListLoaded && allMessages.size() > pageSize) {
            int totalPages = (int) Math.ceil((double) allMessages.size() / pageSize);
            
            html.append("        <div class=\"pagination\">\n");
            
            if (currentPage > 1) {
                html.append("            <a href=\"/?page=").append(currentPage - 1)
                    .append("&pageSize=").append(pageSize)
                    .append("&listAll=true\">&laquo; Previous</a>\n");
            }
            
            // Show first page, current page with neighbors, and last page
            int startPage = Math.max(1, currentPage - 2);
            int endPage = Math.min(totalPages, currentPage + 2);
            
            if (startPage > 1) {
                html.append("            <a href=\"/?page=1&pageSize=").append(pageSize)
                    .append("&listAll=true\">1</a>\n");
                if (startPage > 2) {
                    html.append("            <span>...</span>\n");
                }
            }
            
            for (int i = startPage; i <= endPage; i++) {
                if (i == currentPage) {
                    html.append("            <span class=\"current\">").append(i).append("</span>\n");
                } else {
                    html.append("            <a href=\"/?page=").append(i)
                        .append("&pageSize=").append(pageSize)
                        .append("&listAll=true\">").append(i).append("</a>\n");
                }
            }
            
            if (endPage < totalPages) {
                if (endPage < totalPages - 1) {
                    html.append("            <span>...</span>\n");
                }
                html.append("            <a href=\"/?page=").append(totalPages)
                    .append("&pageSize=").append(pageSize)
                    .append("&listAll=true\">").append(totalPages).append("</a>\n");
            }
            
            if (currentPage < totalPages) {
                html.append("            <a href=\"/?page=").append(currentPage + 1)
                    .append("&pageSize=").append(pageSize)
                    .append("&listAll=true\">Next &raquo;</a>\n");
            }
            
            html.append("        </div>\n");
        }
            
        html.append("    </form>\n")
            .append("    <div class=\"footer\">\n")
            .append("        POP3 Email Client - Last refreshed: ").append(formatDate(lastRefreshTime)).append("\n")
            .append("    </div>\n")
            .append("</body>\n")
            .append("</html>\n");
    
        return html.toString();
    }

    private String formatDate(long timestamp) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp));
    }

    /* ================= HTTP Response Methods ================= */
    private void sendHtmlResponse(OutputStream output, String html) throws IOException {
        String response = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: text/html; charset=utf-8\r\n" +
                         "Content-Length: " + html.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                         "Connection: close\r\n\r\n" +
                         html;
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void sendRedirectResponse(OutputStream output, String location) throws IOException {
        String response = "HTTP/1.1 303 See Other\r\n" +
                         "Location: " + location + "\r\n" +
                         "Connection: close\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void send400Response(OutputStream output, String message) throws IOException {
        String html = String.format("""
            <html>
            <head><title>400 Bad Request</title></head>
            <body>
                <h1>400 Bad Request</h1>
                <p>%s</p>
                <a href="/">Back to inbox</a>
            </body>
            </html>
            """, escapeHtml(message));
        
        String response = "HTTP/1.1 400 Bad Request\r\n" +
                         "Content-Type: text/html; charset=utf-8\r\n" +
                         "Content-Length: " + html.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                         "Connection: close\r\n\r\n" +
                         html;
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void send404Response(OutputStream output) throws IOException {
        String html = """
            <html>
            <head><title>404 Not Found</title></head>
            <body>
                <h1>404 Not Found</h1>
                <p>The requested resource was not found on this server.</p>
                <a href="/">Back to inbox</a>
            </body>
            </html>
            """;
        
        String response = "HTTP/1.1 404 Not Found\r\n" +
                         "Content-Type: text/html; charset=utf-8\r\n" +
                         "Content-Length: " + html.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                         "Connection: close\r\n\r\n" +
                         html;
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void send500Response(OutputStream output, String message) throws IOException {
        String html = String.format("""
            <html>
            <head><title>500 Server Error</title></head>
            <body>
                <h1>500 Server Error</h1>
                <p>%s</p>
                <a href="/">Back to inbox</a>
            </body>
            </html>
            """, escapeHtml(message));
        
        String response = "HTTP/1.1 500 Internal Server Error\r\n" +
                         "Content-Type: text/html; charset=utf-8\r\n" +
                         "Content-Length: " + html.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                         "Connection: close\r\n\r\n" +
                         html;
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /* ================= Utility Methods ================= */
    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        
        for (String param : query.split("&")) {
            int equalsPos = param.indexOf('=');
            if (equalsPos > 0) {
                String key = param.substring(0, equalsPos);
                String value = param.substring(equalsPos + 1);
                params.put(key, value);
            }
        }
        return params;
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    private String truncate(String input, int length) {
        if (input == null) return "";
        return input.length() > length ? input.substring(0, length - 3) + "..." : input;
    }

    private String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /* ================= Cleanup Methods ================= */
    public void stop() {
        running = false;
        
        try {
            // 1. First close web server
            if (webServer != null) {
                webServer.close();
            }
            
            // 2. Proper POP3 quit sequence
            if (pop3Connected) {
                try {
                    sendPOP3Command("QUIT");
                    String response = readPOP3Response(); // Wait for +OK reply
                    System.out.println("POP3 QUIT response: " + response);
                    
                    // Small delay to ensure server processes QUIT
                    Thread.sleep(500); 
                } catch (Exception e) {
                    System.err.println("Error during QUIT: " + e.getMessage());
                } finally {
                    // 3. Close resources
                    pop3Reader.close();
                    pop3Writer.close();
                    pop3Socket.close();
                }
            }
            
            // 4. Shutdown thread pool
            if (threadPool != null) {
                threadPool.shutdown();
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            }
        } catch (Exception e) {
            System.err.println("Shutdown error: " + e.getMessage());
        }
    }

    private Map<Integer, String> getMessageUIDs() throws IOException {
        sendPOP3Command("UIDL");
        List<String> response = readPOP3MultilineResponse();
        
        Map<Integer, String> uidMap = new HashMap<>();
        for (String line : response) {
            if (!line.startsWith("+OK") && !line.equals(".")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    uidMap.put(Integer.parseInt(parts[0]), parts[1]);
                }
            }
        }
        return uidMap;
    }

    static class EmailMessage {
        int id;
        int size;
        String from = "Unknown";
        String subject = "(No subject)";
        String date;
        String body;
    }
}