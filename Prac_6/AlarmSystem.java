import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class AlarmSystem {
    // SMTP server configuration - using localhost and port 25
    private static final String SMTP_SERVER = "localhost";
    private static final int SMTP_PORT = 25;
    
    // Email configuration
    private static final String SENDER_EMAIL = "alarm@localhost";
    private static final String RECIPIENT_EMAIL = System.getProperty("user.name"); // Send to current user
    
    // Sensor configuration
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
        // Bonus Feature 2: Start heartbeat scheduler (every 24 hours)
        ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1);
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeatEmail();
            } catch (IOException e) {
                logger.severe("Heartbeat failed: " + e.getMessage());
            }
        }, 0, 24, TimeUnit.HOURS);

        // Main loop
        displayWelcomeMessage();
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            char key = input.charAt(0);
            if (key == 'q') {
                System.out.println("Shutting down...");
                heartbeatScheduler.shutdown();
                break;
            } else if (key == 'a') {
                changeAlarmState(scanner);
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

    // Bonus Feature 2: Heartbeat Email
    private static void sendHeartbeatEmail() throws IOException {
        String uptime = executeCommand("uptime -p");
        String body = "Alarm System Heartbeat\n" +
                     "Uptime: " + uptime + "\n" +
                     "Last Check: " + new Date() + "\n" +
                     "Status: " + getStateString(currentState);
        sendRawEmail("Heartbeat: System Active", body);
    }

    // Bonus Feature 1: PGP-Encrypted Email
    private static void sendEmailAlert(String sensorName, String zone)throws IOException {
        try {
            String plaintext = "ALERT: " + sensorName + " (" + zone + ") triggered at " + new Date();
            String encryptedBody = encryptWithGPG(plaintext, RECIPIENT_EMAIL);
            
            if (encryptedBody == null) {
                // Fallback to plaintext only if encryption fails
                sendRawEmail("ALERT: " + sensorName + " Triggered", plaintext);
                logger.warning("PGP failed - sent plaintext only");
            } else {
                // Create multipart email with both versions
                String boundary = "==ALARM-BOUNDARY==";
                String emailContent = "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\n\n" +
                    
                    "--" + boundary + "\n" +
                    "Content-Type: text/plain; charset=UTF-8\n\n" +
                    "PLAINTEXT VERSION:\n" + plaintext + "\n\n" +
                    
                    "--" + boundary + "\n" +
                    "Content-Type: application/pgp-encrypted\n" +
                    "Content-Disposition: attachment; filename=\"encrypted.asc\"\n\n" +
                   
                    encryptedBody + 
                    
                    
                    "--" + boundary + "--";
                
                sendRawEmail("ALERT: " + sensorName + " Triggered", emailContent);
                logger.info("Sent multipart alert for " + sensorName);
            }
        } catch (Exception e) {
            logger.severe("Failed to send alert: " + e.getMessage());
        }
    }

    private static String encryptWithGPG(String message, String recipient) throws IOException {
        try {
            // 1. Create temp file
            Path tempFile = Files.createTempFile("alarm-", ".txt");
            Files.writeString(tempFile, message);
    
            // 2. Run GPG with explicit output to stdout
            Process gpg = new ProcessBuilder()
                .command("gpg", "--batch", "--yes", "--encrypt", "--armor",
                        "--recipient", recipient,
                        "--output", "-",  // Force output to stdout
                        tempFile.toString())
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();
    
            // 3. Read output and errors
            String encrypted = new String(gpg.getInputStream().readAllBytes());
            String errors = new String(gpg.getErrorStream().readAllBytes());
            int exitCode = gpg.waitFor();
    
            // 4. Clean up
            Files.delete(tempFile);
    
            // 5. Debug output
            System.out.println("GPG Exit Code: " + exitCode);
            System.out.println("GPG Output Length: " + encrypted.length());
            System.out.println("GPG Errors: " + (errors.isEmpty() ? "None" : errors));
    
            if (exitCode != 0 || encrypted.isEmpty()) {
                throw new IOException("GPG failed: " + errors);
            }
    
            return encrypted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Encryption interrupted", e);
        }
    }

    

    // Core SMTP Implementation (unchanged)
    private static void sendRawEmail(String subject, String body) throws IOException {
        try (Socket socket = new Socket(SMTP_SERVER, SMTP_PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            // SMTP Protocol Handshake
            expectResponse(in, "220");
            out.println("HELO " + InetAddress.getLocalHost().getHostName());
            expectResponse(in, "250");
            out.println("MAIL FROM:<" + SENDER_EMAIL + ">");
            expectResponse(in, "250");
            out.println("RCPT TO:<" + RECIPIENT_EMAIL + ">");
            expectResponse(in, "250");
            out.println("DATA");
            expectResponse(in, "354");

            // Email Headers
            out.println("From: Alarm System <" + SENDER_EMAIL + ">");
            out.println("To: " + RECIPIENT_EMAIL);
            out.println("Subject: " + subject);
            out.println("Date: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(new Date()));
            out.println();
            out.println(body);
            out.println(".");
            expectResponse(in, "250");
            out.println("QUIT");
        }
    }

    // Helper Methods
    private static void expectResponse(BufferedReader in, String expectedCode) throws IOException {
        String response = in.readLine();
        if (!response.startsWith(expectedCode)) {
            throw new IOException("SMTP error: " + response);
        }
    }

    private static String executeCommand(String command) {
        try {
            Process proc = Runtime.getRuntime().exec(command);
            return new String(proc.getInputStream().readAllBytes()).trim();
        } catch (IOException e) {
            return "Unknown";
        }
    }
    private static void displayWelcomeMessage() {
        System.out.println("**** Home Alarm System with Email Notifications ****");
        System.out.println("Current state: " + getStateString(currentState));
        System.out.println("\nMonitoring has started. Available commands:");
        displayHelpMenu();
        System.out.println("\nSystem armed and ready.");
        System.out.println("Emails will be sent to: " + RECIPIENT_EMAIL);
    }
    
    private static void displayHelpMenu() {
        System.out.println("- Sensor keys:");
        for (Map.Entry<Character, String> sensor : SENSORS.entrySet()) {
            System.out.println("  Press '" + sensor.getKey() + "' to trigger " + sensor.getValue() + 
                              " (" + SENSOR_ZONES.get(sensor.getValue()) + " zone)");
        }
        System.out.println("- Command keys:");
        System.out.println("  Press 'a' to change alarm state");
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
            
            if (currentState != STATE_DISARMED && previousState == STATE_DISARMED) {
                System.out.println("System will be fully armed in 30 seconds.");
                
                new Thread(() -> {
                    try {
                        for (int i = 30; i > 0; i -= 5) {
                            System.out.println("Arming in " + i + " seconds...");
                            Thread.sleep(5000);
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
            
            if (isInCooldown(sensorName)) {
                System.out.println("Sensor " + sensorName + " was recently triggered and is in cooldown period.");
                return;
            }
            
            lastTriggeredTime.put(sensorName, System.currentTimeMillis());
            
            if (currentState == STATE_DISARMED) {
                System.out.println("⚠️ " + sensorName + " sensor triggered (system is disarmed, no alarm)");
                logger.info(sensorName + " triggered while system was disarmed");
                return;
            }
            
            if (currentState == STATE_ARMED_HOME && zone.equals("Interior")) {
                System.out.println("⚠️ " + sensorName + " sensor triggered (interior zone ignored in Home mode)");
                logger.info(sensorName + " triggered in Armed-Home mode (ignored)");
                return;
            }
            
            System.out.println("🚨 ALARM: " + sensorName + " sensor triggered!");
            logger.warning("ALARM TRIGGERED: " + sensorName + " (" + zone + " zone)");
            
            try {
                sendEmailAlert(sensorName, zone);
                System.out.println("✓ Email alert sent to " + RECIPIENT_EMAIL);
                System.out.println("Check your mail at /var/mail/" + System.getProperty("user.name"));
            } catch (IOException e) {
                System.out.println("✗ Failed to send email alert: " + e.getMessage());
                logger.severe("Failed to send email alert: " + e.getMessage());
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
    
    private static String getStateString(int state) {
        switch (state) {
            case STATE_DISARMED: return "Disarmed";
            case STATE_ARMED_AWAY: return "Armed (Away)";
            case STATE_ARMED_HOME: return "Armed (Home)";
            default: return "Unknown";
        }
    }
    
    
}