import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * A program that monitors an HTML file for changes and automatically uploads 
 * it to a remote server using pure FTP protocol implementation.
 */
public class FTPMonitor {
    // FTP server details
    private String ftpServer;
    private int ftpPort;
    private String username;
    private String password;
    private String remoteDirectory;
    
    // File to monitor
    private Path fileToMonitor;
    private FileTime lastModifiedTime;
    
    // Time between checks (in seconds)
    private int pollingInterval;
    
    /**
     * Constructs a new FTP monitor
     */
    public FTPMonitor(String ftpServer, int ftpPort, String username, String password, 
                     String remoteDirectory, String localFilePath, int pollingInterval) {
        this.ftpServer = ftpServer;
        this.ftpPort = ftpPort;
        this.username = username;
        this.password = password;
        this.remoteDirectory = remoteDirectory;
        this.fileToMonitor = Paths.get(localFilePath);
        this.pollingInterval = pollingInterval;
        
        try {
            this.lastModifiedTime = Files.getLastModifiedTime(fileToMonitor);
        } catch (IOException e) {
            System.err.println("Error accessing file: " + e.getMessage());
            System.exit(1);
        }
    }
    
    /**
     * Starts monitoring the file for changes
     */
    public void startMonitoring() {
        System.out.println("Monitoring file: " + fileToMonitor);
        System.out.println("FTP server: " + ftpServer + ":" + ftpPort);
        System.out.println("Remote directory: " + remoteDirectory);
        System.out.println("Polling interval: " + pollingInterval + " seconds");
        System.out.println("Press Ctrl+C to exit");
        System.out.println("-------------------------------------");
        
        while (true) {
            try {
                // Check if file has been modified
                FileTime currentModifiedTime = Files.getLastModifiedTime(fileToMonitor);
                
                if (currentModifiedTime.compareTo(lastModifiedTime) > 0) {
                    System.out.println("File change detected at: " + new java.util.Date());
                    uploadFile();
                    lastModifiedTime = currentModifiedTime;
                }
                
                // Wait before next check
                TimeUnit.SECONDS.sleep(pollingInterval);
                
            } catch (IOException e) {
                System.err.println("Error checking file: " + e.getMessage());
            } catch (InterruptedException e) {
                System.err.println("Monitoring interrupted: " + e.getMessage());
                break;
            }
        }
    }
    
    /**
     * Uploads the file to the FTP server
     */
    private void uploadFile() throws IOException {
        Socket controlSocket = null;
        BufferedReader controlReader = null;
        PrintWriter controlWriter = null;
        
        try {
            // Connect to the FTP server control port
            controlSocket = new Socket(ftpServer, ftpPort);
            controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            controlWriter = new PrintWriter(controlSocket.getOutputStream(), true);
            
            // Read welcome message
            String response = readResponse(controlReader);
            if (!response.startsWith("220")) {
                throw new IOException("FTP server not ready: " + response);
            }
            
            // Login
            sendCommand(controlWriter, controlReader, "USER " + username);
            response = sendCommand(controlWriter, controlReader, "PASS " + password);
            if (!response.startsWith("230")) {
                throw new IOException("Login failed: " + response);
            }
            
            // Switch to binary mode
            sendCommand(controlWriter, controlReader, "TYPE I");
            
            // Change directory if specified
            if (remoteDirectory != null && !remoteDirectory.isEmpty()) {
                response = sendCommand(controlWriter, controlReader, "CWD " + remoteDirectory);
                if (!response.startsWith("250")) {
                    throw new IOException("Cannot change directory: " + response);
                }
            }
            
            // Enter passive mode
            response = sendCommand(controlWriter, controlReader, "PASV");
            if (!response.startsWith("227")) {
                throw new IOException("Cannot enter passive mode: " + response);
            }
            
            // Parse passive mode response to get data connection port
            int[] dataConnectionInfo = parsePassiveMode(response);
            String dataHost = dataConnectionInfo[0] + "." + dataConnectionInfo[1] + "." +
                             dataConnectionInfo[2] + "." + dataConnectionInfo[3];
            int dataPort = (dataConnectionInfo[4] * 256) + dataConnectionInfo[5];
            
            // Extract filename from path
            String filename = fileToMonitor.getFileName().toString();
            
            // Send STOR command
            sendCommand(controlWriter, "STOR " + filename);
            
            // Connect to data port and upload file
            Socket dataSocket = new Socket(dataHost, dataPort);
            OutputStream dataOut = dataSocket.getOutputStream();
            
            // Read file contents and write to data socket
            byte[] buffer = Files.readAllBytes(fileToMonitor);
            dataOut.write(buffer);
            dataOut.flush();
            dataOut.close();
            dataSocket.close();
            
            // Read transfer completion response
            response = readResponse(controlReader);
            if (!response.startsWith("150") && !response.startsWith("125")) {
                throw new IOException("Error starting data transfer: " + response);
            }
            
            response = readResponse(controlReader);
            if (!response.startsWith("226")) {
                throw new IOException("Data transfer failed: " + response);
            }
            
            System.out.println("File uploaded successfully!");
            
            // Logout
            sendCommand(controlWriter, controlReader, "QUIT");
            
        } catch (IOException e) {
            System.err.println("FTP upload error: " + e.getMessage());
            throw e;
        } finally {
            // Close all connections
            if (controlWriter != null) controlWriter.close();
            if (controlReader != null) {
                try { controlReader.close(); } catch (IOException e) { /* ignore */ }
            }
            if (controlSocket != null && !controlSocket.isClosed()) {
                try { controlSocket.close(); } catch (IOException e) { /* ignore */ }
            }
        }
    }
    
    /**
     * Sends a command to the FTP server and returns the response
     */
    private String sendCommand(PrintWriter writer, BufferedReader reader, String command) throws IOException {
        writer.println(command);
        return readResponse(reader);
    }
    
    /**
     * Sends a command to the FTP server without waiting for a response
     */
    private void sendCommand(PrintWriter writer, String command) {
        writer.println(command);
    }
    
    /**
     * Reads a multi-line response from the FTP server
     */
    private String readResponse(BufferedReader reader) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;
        
        while (true) {
            line = reader.readLine();
            if (line == null) {
                throw new IOException("Connection closed by server");
            }
            
            response.append(line).append("\n");
            
            // Check if this is the last line of the response
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
        }
        
        System.out.println("SERVER: " + response.toString().trim());
        return response.toString().trim();
    }
    
    /**
     * Parses the PASV mode response to extract host and port information
     */
    private int[] parsePassiveMode(String response) {
        int start = response.indexOf('(');
        int end = response.indexOf(')', start);
        String[] parts = response.substring(start + 1, end).split(",");
        
        int[] result = new int[6];
        for (int i = 0; i < 6; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        
        return result;
    }
    
    /**
     * Main program entry point
     */
    public static void main(String[] args) {
        // Default values
        String ftpServer = "localhost";
        int ftpPort = 21;
        String username = "anonymous";
        String password = "guest";
        String remoteDirectory = "";
        String localFilePath = "";
        int pollingInterval = 30; // seconds
        
        // If command line arguments are provided
        if (args.length > 0) {
            localFilePath = args[0];
            
            if (args.length > 1) ftpServer = args[1];
            if (args.length > 2) username = args[2];
            if (args.length > 3) password = args[3];
            if (args.length > 4) remoteDirectory = args[4];
            if (args.length > 5) pollingInterval = Integer.parseInt(args[5]);
        } else {
            // Interactive mode
            Scanner scanner = new Scanner(System.in);
            
            System.out.print("Enter the full path to the HTML file to monitor: ");
            localFilePath = scanner.nextLine();
            
            System.out.print("Enter FTP server address [default: localhost]: ");
            String input = scanner.nextLine();
            if (!input.isEmpty()) ftpServer = input;
            
            System.out.print("Enter FTP port [default: 21]: ");
            input = scanner.nextLine();
            if (!input.isEmpty()) ftpPort = Integer.parseInt(input);
            
            System.out.print("Enter username [default: anonymous]: ");
            input = scanner.nextLine();
            if (!input.isEmpty()) username = input;
            
            System.out.print("Enter password: ");
            input = scanner.nextLine();
            if (!input.isEmpty()) password = input;
            
            System.out.print("Enter remote directory [default: root]: ");
            input = scanner.nextLine();
            if (!input.isEmpty()) remoteDirectory = input;
            
            System.out.print("Enter polling interval in seconds [default: 30]: ");
            input = scanner.nextLine();
            if (!input.isEmpty()) pollingInterval = Integer.parseInt(input);
            
            scanner.close();
        }
        
        // Check if file exists
        File file = new File(localFilePath);
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: The specified file does not exist: " + localFilePath);
            System.exit(1);
        }
        
        // Create and start the monitor
        FTPMonitor monitor = new FTPMonitor(ftpServer, ftpPort, username, password, 
                                           remoteDirectory, localFilePath, pollingInterval);
        monitor.startMonitoring();
    }
}