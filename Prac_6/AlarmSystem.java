import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.logging.*;

/**
 * Enhanced AlarmSystem - A program that simulates a home alarm system
 * with advanced security features, logging, and notifications
 */
public class AlarmSystem {
    // SMTP server configuration
    private static final String SMTP_SERVER = "localhost";
    private static final int SMTP_PORT = 2525; // Using non-privileged port for testing
    
    // Email configuration
    private static final String SENDER_EMAIL = "alarm@myhouse.com";
    private static final String RECIPIENT_EMAIL = "owner@myhouse.com";
    
    // Sensor configuration - which keys trigger which sensors
    private static final Map<Character, String> SENSORS = new HashMap<>();
    
    // Alarm states
    private static final int STATE_DISARMED = 0;
    private static final int STATE_ARMED_AWAY = 1;
    private static final int STATE_ARMED_HOME = 2;
    private static int currentState = STATE_ARMED_AWAY;
    
    // Security code
    private static final String SECURITY_CODE = "1234";
    
    // Cooldown period for sensors (in seconds)
    private static final int SENSOR_COOLDOWN = 30;
    private static final Map<String, Long> lastTriggeredTime = new ConcurrentHashMap<>();
    
    // Logger for security events
    private static final Logger logger = Logger.getLogger("SecurityLog");
    private static FileHandler fileHandler;
    
    // Notification preferences
    private static boolean notifyByEmail = true;
    private static boolean notifyBySms = false;
    private static String phoneNumber = "";
    
    // SMTP Server instance
    private static SMTPTestServer smtpServer;
    
    // Sensor zones
    private static final Map<String, String> SENSOR_ZONES = new HashMap<>();
    
    static {
        // Initialize sensors
        SENSORS.put('d', "Front Door");
        SENSORS.put('w', "Window");
        SENSORS.put('m', "Motion Detector");
        SENSORS.put('b', "Back Door");
        SENSORS.put('g', "Garage Door");
        
        // Initialize sensor zones
        SENSOR_ZONES.put("Front Door", "Perimeter");
        SENSOR_ZONES.put("Back Door", "Perimeter");
        SENSOR_ZONES.put("Window", "Perimeter");
        SENSOR_ZONES.put("Garage Door", "Perimeter");
        SENSOR_ZONES.put("Motion Detector", "Interior");
        
        // Set up logging
        try {
            fileHandler = new FileHandler("security_log.txt", true);
            SimpleFormatter formatter = new SimpleFormatter();
            fileHandler.setFormatter(formatter);
            logger.addHandler(fileHandler);
            logger.setLevel(Level.ALL);
        } catch (IOException e) {
            System.err.println("Could not set up logging: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        // Start the test SMTP server
        smtpServer = new SMTPTestServer(SMTP_PORT);
        smtpServer.start();
        
        // Schedule a task to run every minute to check system status
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(AlarmSystem::checkSystemStatus, 1, 1, TimeUnit.MINUTES);
        
        displayWelcomeMessage();
        
        // Start monitoring for key presses
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
                continue;
            }
            
            char key = input.charAt(0);
            
            if (key == 'q') {
                System.out.println("Alarm system shutting down.");
                smtpServer.stop();
                scheduler.shutdown();
                try {
                    fileHandler.close();
                } catch (SecurityException e) {
                    // Ignore
                }
                break;
            } else if (key == 'a') {
                changeAlarmState(scanner);
            } else if (key == 'p') {
                changeSettings(scanner);
            } else if (key == 'l') {
                displaySecurityLog();
            } else if (key == 'h') {
                displayHelpMenu();
            } else {
                processSensorInput(key);
            }
        }
        
        scanner.close();
    }
    
    private static void displayWelcomeMessage() {
        System.out.println("**** Enhanced Home Alarm System ****");
        System.out.println("Test SMTP Server started on port " + SMTP_PORT);
        System.out.println("Current state: " + getStateString(currentState));
        System.out.println("\nMonitoring has started. Available commands:");
        displayHelpMenu();
        System.out.println("\nSystem armed and ready.");
    }
    
    private static void displayHelpMenu() {
        System.out.println("- Sensor keys:");
        for (Map.Entry<Character, String> sensor : SENSORS.entrySet()) {
            System.out.println("  Press '" + sensor.getKey() + "' to trigger " + sensor.getValue() + 
                              " (" + SENSOR_ZONES.get(sensor.getValue()) + " zone)");
        }
        System.out.println("- Command keys:");
        System.out.println("  Press 'a' to change alarm state");
        System.out.println("  Press 'p' to change system preferences");
        System.out.println("  Press 'l' to view security log");
        System.out.println("  Press 'h' to show this help menu");
        System.out.println("  Press 'q' to quit the program");
    }
    
    private static void changeAlarmState(Scanner scanner) {
        System.out.println("\nEnter security code:");
        String code = scanner.nextLine().trim();
        
        if (!code.equals(SECURITY_CODE)) {
            System.out.println("❌ Incorrect security code!");
            logger.warning("Failed attempt to change alarm state - incorrect code entered");
            return;
        }
        
        System.out.println("\nSelect new state:");
        System.out.println("1. Disarmed");
        System.out.println("2. Armed - Away");
        System.out.println("3. Armed - Home");
        System.out.print("> ");
        
        try {
            int choice = Integer.parseInt(scanner.nextLine().trim());
            int previousState = currentState;
            
            switch (choice) {
                case 1:
                    currentState = STATE_DISARMED;
                    break;
                case 2:
                    currentState = STATE_ARMED_AWAY;
                    break;
                case 3:
                    currentState = STATE_ARMED_HOME;
                    break;
                default:
                    System.out.println("Invalid choice. State unchanged.");
                    return;
            }
            
            System.out.println("✓ Alarm state changed to: " + getStateString(currentState));
            logger.info("Alarm state changed from " + getStateString(previousState) + 
                       " to " + getStateString(currentState));
            
            // If system is now armed, set a delay before it becomes active
            if (currentState != STATE_DISARMED && previousState == STATE_DISARMED) {
                System.out.println("System will be fully armed in 30 seconds.");
                
                // Start a thread to wait for the arming delay
                new Thread(() -> {
                    try {
                        // Countdown for arming
                        for (int i = 30; i > 0; i -= 5) {
                            System.out.println("Arming in " + i + " seconds...");
                            Thread.sleep(5000); // 5 seconds
                        }
                        System.out.println("⚠️ System is now fully armed!");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
            
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. State unchanged.");
        }
    }
    
    private static void changeSettings(Scanner scanner) {
        System.out.println("\nPreferences Menu:");
        System.out.println("1. Email notifications: " + (notifyByEmail ? "ON" : "OFF"));
        System.out.println("2. SMS notifications: " + (notifyBySms ? "ON" : "OFF"));
        System.out.println("3. Change phone number (current: " + 
                          (phoneNumber.isEmpty() ? "not set" : phoneNumber) + ")");
        System.out.println("4. Back to main menu");
        System.out.print("> ");
        
        try {
            int choice = Integer.parseInt(scanner.nextLine().trim());
            
            switch (choice) {
                case 1:
                    notifyByEmail = !notifyByEmail;
                    System.out.println("Email notifications " + (notifyByEmail ? "enabled" : "disabled"));
                    break;
                case 2:
                    notifyBySms = !notifyBySms;
                    System.out.println("SMS notifications " + (notifyBySms ? "enabled" : "disabled"));
                    if (notifyBySms && phoneNumber.isEmpty()) {
                        System.out.println("Please set your phone number.");
                        changePhoneNumber(scanner);
                    }
                    break;
                case 3:
                    changePhoneNumber(scanner);
                    break;
                case 4:
                    return;
                default:
                    System.out.println("Invalid choice.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid input.");
        }
    }
    
    private static void changePhoneNumber(Scanner scanner) {
        System.out.println("Enter new phone number (or leave empty to cancel):");
        String newNumber = scanner.nextLine().trim();
        
        if (!newNumber.isEmpty()) {
            phoneNumber = newNumber;
            System.out.println("✓ Phone number updated to: " + phoneNumber);
        }
    }
    
    private static void displaySecurityLog() {
        System.out.println("\n========== SECURITY LOG ==========");
        try {
            List<String> logLines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new FileReader("security_log.txt"));
            String line;
            while ((line = reader.readLine()) != null) {
                logLines.add(line);
            }
            reader.close();
            
            // Display the last 10 log entries (or all if fewer than 10)
            int startIndex = Math.max(0, logLines.size() - 10);
            for (int i = startIndex; i < logLines.size(); i++) {
                System.out.println(logLines.get(i));
            }
            
        } catch (IOException e) {
            System.out.println("Error reading log file: " + e.getMessage());
        }
        System.out.println("=================================\n");
    }
    
    private static void processSensorInput(char key) {
        if (SENSORS.containsKey(key)) {
            String sensorName = SENSORS.get(key);
            String zone = SENSOR_ZONES.get(sensorName);
            
            // Check if the sensor is in cooldown period
            if (isInCooldown(sensorName)) {
                System.out.println("Sensor " + sensorName + " was recently triggered and is in cooldown period.");
                return;
            }
            
            // Update the last triggered time
            lastTriggeredTime.put(sensorName, System.currentTimeMillis());
            
            // Check if we should ignore this sensor based on the current state
            if (currentState == STATE_DISARMED) {
                System.out.println("⚠️ " + sensorName + " sensor triggered (system is disarmed, no alarm)");
                logger.info(sensorName + " triggered while system was disarmed");
                return;
            }
            
            // In "Armed-Home" mode, ignore interior sensors
            if (currentState == STATE_ARMED_HOME && zone.equals("Interior")) {
                System.out.println("⚠️ " + sensorName + " sensor triggered (interior zone ignored in Home mode)");
                logger.info(sensorName + " triggered in Armed-Home mode (ignored)");
                return;
            }
            
            // If we get here, the alarm is triggered
            System.out.println("🚨 ALARM: " + sensorName + " sensor triggered!");
            logger.warning("ALARM TRIGGERED: " + sensorName + " (" + zone + " zone)");
            
            // Send notifications
            if (notifyByEmail) {
                try {
                    sendEmailAlert(sensorName, zone);
                    System.out.println("✓ Email alert sent successfully");
                } catch (IOException e) {
                    System.out.println("✗ Failed to send email alert: " + e.getMessage());
                    logger.severe("Failed to send email alert: " + e.getMessage());
                }
            }
            
            if (notifyBySms && !phoneNumber.isEmpty()) {
                try {
                    sendSmsAlert(sensorName, zone);
                    System.out.println("✓ SMS alert sent successfully");
                } catch (Exception e) {
                    System.out.println("✗ Failed to send SMS alert: " + e.getMessage());
                    logger.severe("Failed to send SMS alert: " + e.getMessage());
                }
            }
        } else {
            System.out.println("Unknown command: " + key);
        }
    }
    
    private static boolean isInCooldown(String sensorName) {
        Long lastTriggered = lastTriggeredTime.get(sensorName);
        if (lastTriggered == null) {
            return false;
        }
        
        long elapsedSeconds = (System.currentTimeMillis() - lastTriggered) / 1000;
        return elapsedSeconds < SENSOR_COOLDOWN;
    }
    
    private static void checkSystemStatus() {
        // Simulates a periodic system check
        logger.fine("System status check: state=" + getStateString(currentState));
        
        // Check for low battery, internet connection issues, etc.
        Random random = new Random();
        if (random.nextInt(100) < 5) { // 5% chance of detecting an issue
            String issue = random.nextInt(2) == 0 ? "Low battery detected" : "Internet connection unstable";
            logger.warning("System issue detected: " + issue);
            System.out.println("\n⚠️ System issue: " + issue);
        }
    }
    
    private static String getStateString(int state) {
        switch (state) {
            case STATE_DISARMED: return "Disarmed";
            case STATE_ARMED_AWAY: return "Armed (Away)";
            case STATE_ARMED_HOME: return "Armed (Home)";
            default: return "Unknown";
        }
    }
    
    /**
     * Sends an SMS alert (simulation)
     */
    private static void sendSmsAlert(String sensorName, String zone) {
        // This is a simulation of sending an SMS
        System.out.println("\n========== SIMULATED SMS ==========");
        System.out.println("To: " + phoneNumber);
        System.out.println("ALARM TRIGGERED: " + sensorName + " in " + zone + " zone");
        System.out.println("Time: " + new Date());
        System.out.println("==================================\n");
        
        // In a real implementation, this would connect to an SMS gateway API
    }
    
    /**
     * Sends an email alert using raw SMTP commands
     * 
     * @param sensorName The name of the triggered sensor
     * @param zone The zone the sensor belongs to
     * @throws IOException If there's an error in the network communication
     */
    private static void sendEmailAlert(String sensorName, String zone) throws IOException {
        // Open socket connection to the SMTP server
        Socket socket = new Socket(SMTP_SERVER, SMTP_PORT);
        
        // Set up the input and output streams
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        
        // Read the welcome message
        String response = in.readLine();
        // System.out.println("SERVER: " + response);
        
        if (!response.startsWith("220")) {
            throw new IOException("Invalid server response: " + response);
        }
        
        // HELO command
        String hostname = InetAddress.getLocalHost().getHostName();
        out.println("HELO " + hostname);
        response = in.readLine();
        // System.out.println("SERVER: " + response);
        
        if (!response.startsWith("250")) {
            throw new IOException("HELO command failed: " + response);
        }
        
        // MAIL FROM command
        out.println("MAIL FROM:<" + SENDER_EMAIL + ">");
        response = in.readLine();
        // System.out.println("SERVER: " + response);
        
        if (!response.startsWith("250")) {
            throw new IOException("MAIL FROM command failed: " + response);
        }
        
        // RCPT TO command
        out.println("RCPT TO:<" + RECIPIENT_EMAIL + ">");
        response = in.readLine();
        // System.out.println("SERVER: " + response);
        
        if (!response.startsWith("250")) {
            throw new IOException("RCPT TO command failed: " + response);
        }
        
        // DATA command
        out.println("DATA");
        response = in.readLine();
        // System.out.println("SERVER: " + response);
        
        if (!response.startsWith("354")) {
            throw new IOException("DATA command failed: " + response);
        }
        
        // Email headers and content
        Date now = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");
        String date = dateFormat.format(now);
        
        // Generate a unique message ID for the email
        String messageId = "<" + UUID.randomUUID().toString() + "@myhouse.com>";
        
        out.println("From: Home Alarm System <" + SENDER_EMAIL + ">");
        out.println("To: Home Owner <" + RECIPIENT_EMAIL + ">");
        out.println("Subject: SECURITY ALERT - " + sensorName + " Triggered");
        out.println("Date: " + date);
        out.println("Message-ID: " + messageId);
        out.println("X-Priority: 1 (Highest)");
        out.println("Importance: high");
        out.println("X-Alarm-Zone: " + zone);
        out.println();
        out.println("SECURITY ALERT");
        out.println("==============");
        out.println();
        out.println("Your " + sensorName + " (" + zone + " zone) has been triggered at " + now + ".");
        out.println();
        out.println("Alarm System Status: " + getStateString(currentState));
        out.println();
        out.println("Please take appropriate action immediately.");
        out.println("If this is a false alarm, enter your security code to disarm the system.");
        out.println();
        out.println("--");
        out.println("This message was automatically generated by your Enhanced Home Alarm System.");
        out.println(".");  // End of message marker for SMTP
        
        response = in.readLine();
        // System.out.println("SERVER: " + response);
        
        if (!response.startsWith("250")) {
            throw new IOException("Message body failed: " + response);
        }
        
        // QUIT command
        out.println("QUIT");
        response = in.readLine();
        // System.out.println("SERVER: " + response);
        
        // Close the connection
        socket.close();
    }
    
    /**
     * A simple SMTP server implementation for testing
     */
    static class SMTPTestServer {
        private final int port;
        private ServerSocket serverSocket;
        private boolean running;
        private final ExecutorService executorService;
        private final List<String> receivedEmails = Collections.synchronizedList(new ArrayList<>());
        
        public SMTPTestServer(int port) {
            this.port = port;
            this.executorService = Executors.newCachedThreadPool();
        }
        
        public void start() {
            try {
                serverSocket = new ServerSocket(port);
                running = true;
                
                // Start accepting connections in a separate thread
                executorService.submit(() -> {
                    while (running) {
                        try {
                            Socket clientSocket = serverSocket.accept();
                            handleClient(clientSocket);
                        } catch (IOException e) {
                            if (running) {
                                System.err.println("Error accepting client connection: " + e.getMessage());
                            }
                        }
                    }
                });
                
                System.out.println("SMTP Test Server started on port " + port);
            } catch (IOException e) {
                System.err.println("Could not start SMTP server: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        public void stop() {
            running = false;
            executorService.shutdown();
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing server socket: " + e.getMessage());
            }
            System.out.println("SMTP Test Server stopped");
        }
        
        private void handleClient(Socket clientSocket) {
            executorService.submit(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                    
                    // Send welcome message
                    out.println("220 localhost SMTP Test Server Ready");
                    
                    String line;
                    StringBuilder emailContent = new StringBuilder();
                    boolean dataMode = false;
                    
                    while ((line = in.readLine()) != null) {
                        if (dataMode) {
                            // In DATA mode, collect email content until single "." is seen
                            if (line.equals(".")) {
                                dataMode = false;
                                receivedEmails.add(emailContent.toString());
                                out.println("250 OK: Message accepted");
                                emailContent = new StringBuilder();
                                System.out.println("Email received and stored (#" + receivedEmails.size() + ")");
                                displayLastEmail();
                            } else {
                                emailContent.append(line).append("\n");
                            }
                        } else {
                            // Normal command mode
                            // System.out.println("CLIENT: " + line);
                            
                            if (line.toUpperCase().startsWith("HELO")) {
                                out.println("250 localhost Hello " + line.substring(5));
                            } else if (line.toUpperCase().startsWith("MAIL FROM:")) {
                                out.println("250 OK");
                            } else if (line.toUpperCase().startsWith("RCPT TO:")) {
                                out.println("250 OK");
                            } else if (line.toUpperCase().equals("DATA")) {
                                out.println("354 End data with <CR><LF>.<CR><LF>");
                                dataMode = true;
                            } else if (line.toUpperCase().equals("QUIT")) {
                                out.println("221 localhost Service closing transmission channel");
                                break;
                            } else {
                                out.println("500 Command not recognized");
                            }
                        }
                    }
                    
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error handling client: " + e.getMessage());
                }
            });
        }
        
        private void displayLastEmail() {
            if (!receivedEmails.isEmpty()) {
                String email = receivedEmails.get(receivedEmails.size() - 1);
                System.out.println("\n========== RECEIVED EMAIL ==========");
                System.out.println(email);
                System.out.println("====================================\n");
            }
        }
        
        public List<String> getReceivedEmails() {
            return receivedEmails;
        }
    }
}