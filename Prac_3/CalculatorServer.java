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
    private static List<String> history = new ArrayList<>(); // Store calculation history

    public static void main(String[] args) {
        try {
            System.out.println("Starting Calculator Server...");
            System.out.println("HTTP Server is running on port " + PORT);

            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleClient(clientSocket)).start();
                }
            } catch (IOException e) {
                System.err.println("HTTP Server error: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Server initialization error: " + e.getMessage());
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             OutputStream out = clientSocket.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;

            String[] requestParts = requestLine.split(" ");
            String method = requestParts[0];
            String path = requestParts[1];

            // Handle HEAD request
            if (method.equals("HEAD")) {
                sendHeaders(out, 200, "OK");
                return;
            }

            // Handle GET request
            if (method.equals("GET")) {
                // Parse cookies for persistent state
                Map<String, String> cookies = parseCookies(in);
                if (cookies.containsKey("calculatorState")) {
                    restoreState(cookies.get("calculatorState"));
                }

                // Handle history page
                if (path.equals("/history")) {
                    sendHistoryPage(out);
                    return;
                }

                // Process the calculator action
                if (path.length() > 1) {
                    String action = path.substring(1);
                    if (!isValidAction(action)) {
                        sendErrorResponse(out, 400, "Invalid Action");
                        return;
                    }
                    processAction(action);


                }

                // Send the response with updated state
                sendCalculatorPage(out);
            } else {
                sendErrorResponse(out, 400, "Bad Request");
            }
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        }
    }

    private static void sendRedirect(OutputStream out, String location) throws IOException {
        String headers = "HTTP/1.1 302 Found\r\n" +
                        "Location: " + location + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
        out.write(headers.getBytes("UTF-8"));
        out.flush();
    }

    private static void sendHistoryPage(OutputStream out) throws IOException {
        StringBuilder html = new StringBuilder();

        html.append("HTTP/1.1 200 OK\r\n");
        html.append("Content-Type: text/html\r\n");
        html.append("Connection: close\r\n");
        html.append("\r\n");

        html.append("<!DOCTYPE html>\r\n");
        html.append("<html>\r\n");
        html.append("<head>\r\n");
        html.append("<title>Calculation History</title>\r\n");
        html.append("<style>\r\n");
        html.append("body { font-family: Arial, sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background-color: #f4f4f4; }\r\n");
        html.append(".history { width: 300px; background-color: #fff; border: 1px solid #ccc; border-radius: 10px; box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1); padding: 20px; }\r\n");
        html.append("</style>\r\n");
        html.append("</head>\r\n");
        html.append("<body>\r\n");
        html.append("<div class=\"history\">\r\n");
        html.append("<h2>Calculation History</h2>\r\n");
        html.append("<ul>\r\n");
        for (String entry : history) {
            html.append("<li>").append(entry).append("</li>\r\n");
        }
        html.append("</ul>\r\n");
        html.append("<a href=\"/\" class=\"button\">Back to Calculator</a>\r\n");
        html.append("</div>\r\n");
        html.append("</body>\r\n");
        html.append("</html>\r\n");

        out.write(html.toString().getBytes("UTF-8"));
        out.flush();
    }

    private static Map<String, String> parseCookies(BufferedReader in) throws IOException {
        Map<String, String> cookies = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("Cookie: ")) {
                String[] cookiePairs = line.substring(8).split("; ");
                for (String pair : cookiePairs) {
                    String[] keyValue = pair.split("=");
                    if (keyValue.length == 2) {
                        cookies.put(keyValue[0], keyValue[1]);
                    }
                }
            }
        }
        return cookies;
    }

    private static void restoreState(String state) {
        String[] parts = state.split("\\|");
        if (parts.length == 5) {
            currentDisplay = parts[0];
            firstOperand = parts[1];
            secondOperand = parts[2];
            operator = parts[3];
            newOperand = Boolean.parseBoolean(parts[4]);
        }
    }

    private static String getState() {
        return currentDisplay + "|" + firstOperand + "|" + secondOperand + "|" + operator + "|" + newOperand;
    }

    private static boolean isValidAction(String action) {
        return action.matches("[0-9]") || action.matches("[+\\-*/]") || action.equals("=") || action.equals("C") || action.equals("div");
    }

    private static void processAction(String action) {
        if (action.equals("div")) {
            action = "/";
        }

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
        } else if (action.matches("[+\\-*/]")) {
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
        } else if (action.equals("=")) {
            if (!firstOperand.isEmpty() && !secondOperand.isEmpty() && !operator.isEmpty()) {
                calculate();
                // Add calculation to history
                history.add(firstOperand + " " + operator + " " + secondOperand + " = " + currentDisplay);
                firstOperand = currentDisplay;
                secondOperand = "";
                operator = "";
                calculationPerformed = true;

                
            }
        } else if (action.equals("C")) {
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
            firstOperand = Double.toString(num1);
            secondOperand = Double.toString(num2);
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

            if (result == (long) result) {
                currentDisplay = String.valueOf((long) result);
            } else {
                currentDisplay = String.valueOf(result);
            }
        } catch (NumberFormatException e) {
            currentDisplay = "Error";
        }
    }

    private static void sendHeaders(OutputStream out, int statusCode, String statusMessage) throws IOException {
        String headers = "HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n" +
                         "Content-Type: text/html\r\n" +
                         "Connection: close\r\n" +
                         "\r\n";
        out.write(headers.getBytes("UTF-8"));
        out.flush();
    }

    private static void sendCalculatorPage(OutputStream out) throws IOException {
        StringBuilder html = new StringBuilder();

        html.append("HTTP/1.1 200 OK\r\n");
        html.append("Content-Type: text/html\r\n");
        html.append("Set-Cookie: calculatorState=" + getState() + "; Path=/\r\n");
        html.append("Connection: close\r\n");
        html.append("\r\n");

        html.append("<!DOCTYPE html>\r\n");
        html.append("<html>\r\n");
        html.append("<head>\r\n");
        html.append("<title>Simple Calculator</title>\r\n");
        html.append("<style>\r\n");
        html.append("body { font-family: Arial, sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background-color: #f4f4f4; }\r\n");
        html.append(".calculator { width: 300px; background-color: #fff; border: 1px solid #ccc; border-radius: 10px; box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1); padding: 20px; }\r\n");
        html.append(".display { width: 100%; height: 60px; background-color: #e0e0e0; border: 1px solid #ccc; border-radius: 5px; margin-bottom: 20px; text-align: right; font-size: 28px; padding: 10px; box-sizing: border-box; }\r\n");
        html.append(".buttons { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }\r\n");
        html.append(".button { background-color: #f0f0f0; border: 1px solid #ccc; border-radius: 5px; padding: 20px; text-align: center; cursor: pointer; font-size: 18px; transition: background-color 0.2s; }\r\n");
        html.append(".button:hover { background-color: #d0d0d0; }\r\n");
        html.append(".clear { grid-column: span 2; background-color: #ff6666; color: white; }\r\n");
        html.append(".equals { background-color: #66cc66; color: white; }\r\n");
        html.append(".operator { background-color: #ffcc66; color: white; }\r\n");
        html.append("</style>\r\n");
        html.append("</head>\r\n");
        html.append("<body>\r\n");
        html.append("<div class=\"calculator\">\r\n");
        html.append("<div class=\"display\">").append(currentDisplay).append("</div>\r\n");
        html.append("<div class=\"buttons\">\r\n");
        html.append("<a href=\"/C\" class=\"button clear\">Clear</a>\r\n");
        html.append("<a href=\"/div\" class=\"button operator\">/</a>\r\n");
        html.append("<a href=\"/*\" class=\"button operator\">*</a>\r\n");
        html.append("<a href=\"/7\" class=\"button\">7</a>\r\n");
        html.append("<a href=\"/8\" class=\"button\">8</a>\r\n");
        html.append("<a href=\"/9\" class=\"button\">9</a>\r\n");
        html.append("<a href=\"/-\" class=\"button operator\">-</a>\r\n");
        html.append("<a href=\"/4\" class=\"button\">4</a>\r\n");
        html.append("<a href=\"/5\" class=\"button\">5</a>\r\n");
        html.append("<a href=\"/6\" class=\"button\">6</a>\r\n");
        html.append("<a href=\"/+\" class=\"button operator\">+</a>\r\n");
        html.append("<a href=\"/1\" class=\"button\">1</a>\r\n");
        html.append("<a href=\"/2\" class=\"button\">2</a>\r\n");
        html.append("<a href=\"/3\" class=\"button\">3</a>\r\n");
        html.append("<a href=\"/=\" class=\"button equals\">=</a>\r\n");
        html.append("<a href=\"/history\" class=\"button\">History</a>\r\n");
        html.append("<a href=\"/0\" class=\"button\" style=\"grid-column: span 3;\">0</a>\r\n");
        html.append("</div>\r\n");
        html.append("</div>\r\n");
        html.append("</body>\r\n");
        html.append("</html>\r\n");

        out.write(html.toString().getBytes("UTF-8"));
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