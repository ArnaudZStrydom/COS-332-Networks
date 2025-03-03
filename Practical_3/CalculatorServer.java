import java.io.*;
import java.net.*;
import java.util.*;

public class CalculatorServer {
    private static final int PORT = 8080;
    private static String currentDisplay = "0";
    private static String firstOperand = "";
    private static String secondOperand = "";
    private static String operator = "";
    private static boolean newOperand = true;
    private static boolean calculationPerformed = false;

    public static void main(String[] args) {
        try {
            System.out.println("Starting Calculator Server...");
            System.out.println("Attempting to bind to port " + PORT);
            
            ServerSocket serverSocket = new ServerSocket(PORT);
            
            System.out.println("SUCCESS: Calculator server started successfully");
            System.out.println("Access the calculator at: http://localhost:" + PORT);
            System.out.println("Or try: http://127.0.0.1:" + PORT);
            System.out.println("Server is now listening for connections...");

            while (true) {
                try {
                    System.out.println("Waiting for client connection...");
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Client connected from: " + clientSocket.getInetAddress().getHostAddress());
                    
                    handleClient(clientSocket);
                    clientSocket.close();
                    System.out.println("Client connection handled and closed");
                } catch (IOException e) {
                    System.err.println("Error handling client connection: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (BindException e) {
            System.err.println("ERROR: Could not bind to port " + PORT);
            System.err.println("The port may already be in use by another application.");
            System.err.println("Try modifying the PORT constant to a different value (e.g., 3000, 8888)");
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("ERROR: Could not start server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket clientSocket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        OutputStream out = clientSocket.getOutputStream();

        // Read the request
        String requestLine = in.readLine();
        System.out.println("Received request: " + requestLine);
        
        if (requestLine == null || !requestLine.startsWith("GET")) {
            System.out.println("Invalid request received. Sending 400 Bad Request.");
            sendErrorResponse(out, 400, "Bad Request");
            return;
        }

        // Process remaining headers (consume them)
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // Just reading through all headers
        }

        // Parse the request
        String path = requestLine.split(" ")[1];
        System.out.println("Processing path: " + path);
        
        if (path.equals("/favicon.ico")) {
            System.out.println("Favicon request ignored");
            sendErrorResponse(out, 404, "Not Found");
            return;
        }

        // Process the calculator action
        if (path.length() > 1) {
            String action = path.substring(1);
            System.out.println("Processing calculator action: " + action);
            
            // Handle special cases for URL encoding
            if (action.equals("div")) {
                action = "/";  // Map "div" to division operator
            }
            
            processAction(action);
        } else {
            System.out.println("Root path requested, showing calculator");
        }

        // Send the response
        System.out.println("Sending calculator page with current display: " + currentDisplay);
        sendCalculatorPage(out);
    }

    private static void processAction(String action) {
        // Handle digit input
        if (action.matches("[0-9]")) {
            if (newOperand || currentDisplay.equals("0") || calculationPerformed) {
                currentDisplay = action;
                newOperand = false;
                calculationPerformed = false;
            } else {
                currentDisplay += action;
            }
            
            if (operator.isEmpty()) {
                firstOperand = currentDisplay;
            } else {
                secondOperand = currentDisplay;
            }
        } 
        // Handle operators
        else if (action.matches("[+\\-*/]")) {
            if (!firstOperand.isEmpty() && !secondOperand.isEmpty()) {
                calculate();
                firstOperand = currentDisplay;
                secondOperand = "";
            } else if (currentDisplay.equals("0")) {
                firstOperand = "0";
            } else {
                firstOperand = currentDisplay;
            }
            operator = action;
            newOperand = true;
        }
        // Handle equals operation
        else if (action.equals("=")) {
            if (!firstOperand.isEmpty() && !secondOperand.isEmpty() && !operator.isEmpty()) {
                calculate();
                firstOperand = currentDisplay;
                secondOperand = "";
                operator = "";
                calculationPerformed = true;
            }
        }
        // Handle clear operation
        else if (action.equals("C")) {
            currentDisplay = "0";
            firstOperand = "";
            secondOperand = "";
            operator = "";
            newOperand = true;
        }
    }

    private static void calculate() {
        try {
            double num1 = Double.parseDouble(firstOperand);
            double num2 = Double.parseDouble(secondOperand);
            double result = 0;

            switch (operator) {
                case "+":
                    result = num1 + num2;
                    break;
                case "-":
                    result = num1 - num2;
                    break;
                case "*":
                    result = num1 * num2;
                    break;
                case "/":
                    if (num2 != 0) {
                        result = num1 / num2;
                    } else {
                        currentDisplay = "Error";
                        return;
                    }
                    break;
            }

            // Format the result: remove decimal part if it's an integer
            if (result == (long) result) {
                currentDisplay = String.valueOf((long) result);
            } else {
                currentDisplay = String.valueOf(result);
            }
        } catch (NumberFormatException e) {
            currentDisplay = "Error";
        }
    }

    private static void sendCalculatorPage(OutputStream out) throws IOException {
        StringBuilder html = new StringBuilder();
        
        html.append("HTTP/1.1 200 OK\r\n");
        html.append("Content-Type: text/html\r\n");
        html.append("Connection: close\r\n");
        html.append("\r\n");
        
        html.append("<!DOCTYPE html>\r\n");
        html.append("<html>\r\n");
        html.append("<head>\r\n");
        html.append("<title>Simple Calculator</title>\r\n");
        html.append("<meta http-equiv=\"Cache-Control\" content=\"no-cache, no-store, must-revalidate\">\r\n");
        html.append("<meta http-equiv=\"Pragma\" content=\"no-cache\">\r\n");
        html.append("<meta http-equiv=\"Expires\" content=\"0\">\r\n");
        html.append("<style>\r\n");
        html.append("body { font-family: Arial, sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }\r\n");
        html.append(".calculator { width: 300px; background-color: #f0f0f0; border: 1px solid #ccc; border-radius: 5px; padding: 10px; }\r\n");
        html.append(".display { width: 100%; height: 50px; background-color: white; border: 1px solid #ccc; margin-bottom: 10px; text-align: right; font-size: 24px; padding: 5px; box-sizing: border-box; }\r\n");
        html.append(".buttons { display: grid; grid-template-columns: repeat(4, 1fr); gap: 5px; }\r\n");
        html.append(".button { background-color: #e0e0e0; border: 1px solid #ccc; padding: 10px; text-align: center; cursor: pointer; text-decoration: none; color: black; font-size: 18px; }\r\n");
        html.append(".button:hover { background-color: #d0d0d0; }\r\n");
        html.append(".clear { grid-column: span 2; }\r\n");
        html.append(".equals { background-color: #4CAF50; color: white; }\r\n");
        html.append(".equals:hover { background-color: #45a049; }\r\n");
        html.append(".operator { background-color: #f8a100; color: white; }\r\n");
        html.append(".operator:hover { background-color: #e59400; }\r\n");
        html.append("</style>\r\n");
        html.append("</head>\r\n");
        html.append("<body>\r\n");
        html.append("<div class=\"calculator\">\r\n");
        html.append("<div class=\"display\">").append(currentDisplay).append("</div>\r\n");
        html.append("<div class=\"buttons\">\r\n");
        
        // Clear button
        html.append("<a href=\"/C\" class=\"button clear\">Clear</a>\r\n");
        
        // Division and multiplication - Changed division to use "div" instead of "/"
        html.append("<a href=\"/div\" class=\"button operator\">/</a>\r\n");
        html.append("<a href=\"/*\" class=\"button operator\">*</a>\r\n");
        
        // Number buttons 7-9 and subtraction
        html.append("<a href=\"/7\" class=\"button\">7</a>\r\n");
        html.append("<a href=\"/8\" class=\"button\">8</a>\r\n");
        html.append("<a href=\"/9\" class=\"button\">9</a>\r\n");
        html.append("<a href=\"/-\" class=\"button operator\">-</a>\r\n");
        
        // Number buttons 4-6 and addition
        html.append("<a href=\"/4\" class=\"button\">4</a>\r\n");
        html.append("<a href=\"/5\" class=\"button\">5</a>\r\n");
        html.append("<a href=\"/6\" class=\"button\">6</a>\r\n");
        html.append("<a href=\"/+\" class=\"button operator\">+</a>\r\n");
        
        // Number buttons 1-3 and equals
        html.append("<a href=\"/1\" class=\"button\">1</a>\r\n");
        html.append("<a href=\"/2\" class=\"button\">2</a>\r\n");
        html.append("<a href=\"/3\" class=\"button\">3</a>\r\n");
        html.append("<a href=\"/=\" class=\"button equals\">=</a>\r\n");
        
        // Number button 0
        html.append("<a href=\"/0\" class=\"button\" style=\"grid-column: span 3;\">0</a>\r\n");
        
        html.append("</div>\r\n");
        html.append("</div>\r\n");
        html.append("</body>\r\n");
        html.append("</html>\r\n");
        
        byte[] response = html.toString().getBytes("UTF-8");
        out.write(response);
        out.flush();
    }

    private static void sendErrorResponse(OutputStream out, int statusCode, String statusMessage) throws IOException {
        String response = "HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n" +
                         "Content-Type: text/html\r\n" +
                         "Connection: close\r\n" +
                         "\r\n" +
                         "<html><body><h1>" + statusCode + " " + statusMessage + "</h1></body></html>";
        
        out.write(response.getBytes("UTF-8"));
        out.flush();
    }
}