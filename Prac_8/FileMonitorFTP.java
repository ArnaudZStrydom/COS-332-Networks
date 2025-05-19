import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * FileMonitorFTP - A program that monitors an HTML file for changes and automatically
 * uploads it to an FTP server when changes are detected.
 * 
 * This implementation uses raw sockets to communicate with the FTP server,
 * following the FTP protocol specifications without using any pre-existing FTP client libraries.
 */
public class FileMonitorFTP {
    // FTP server configuration
    private String ftpServer;
    private int ftpPort;
    private String ftpUsername;
    private String ftpPassword;
    private String remoteDirectory;
    
    // File to monitor
    private String filePath;
    private FileTime lastModifiedTime;
    
    // Polling interval in milliseconds
    private long pollingInterval;
    
    /**
     * Constructor for the FileMonitorFTP class.
     * 
     * @param ftpServer        FTP server address
     * @param ftpPort          FTP server port
     * @param ftpUsername      FTP username
     * @param ftpPassword      FTP password
     * @param remoteDirectory  Remote directory to upload to
     * @param filePath         Path to the HTML file to monitor
     * @param pollingInterval  Polling interval in milliseconds
     */
    public FileMonitorFTP(String ftpServer, int ftpPort, String ftpUsername, String ftpPassword,
                         String remoteDirectory, String filePath, long pollingInterval) {
        this.ftpServer = ftpServer;
        this.ftpPort = ftpPort;
        this.ftpUsername = ftpUsername;
        this.ftpPassword = ftpPassword;
        this.remoteDirectory = remoteDirectory;
        this.filePath = filePath;
        this.pollingInterval = pollingInterval;
        
        try {
            Path path = Paths.get(filePath);
            this.lastModifiedTime = Files.getLastModifiedTime(path);
            System.out.println("Initial file modification time: " + this.lastModifiedTime);
        } catch (IOException e) {
            System.err.println("Error getting file modification time: " + e.getMessage());
            System.exit(1);
        }
    }
    
    /**
     * Start monitoring the file for changes.
     */
    public void startMonitoring() {
        System.out.println("Starting to monitor file: " + filePath);
        System.out.println("Polling every " + pollingInterval/1000 + " seconds");
        
        while (true) {
            try {
                Path path = Paths.get(filePath);
                FileTime currentModifiedTime = Files.getLastModifiedTime(path);
                
                if (currentModifiedTime.compareTo(lastModifiedTime) > 0) {
                    System.out.println("File changed at: " + currentModifiedTime);
                    System.out.println("Uploading file to FTP server...");
                    
                    uploadFile();
                    lastModifiedTime = currentModifiedTime;
                }
                
                Thread.sleep(pollingInterval);
            } catch (IOException e) {
                System.err.println("Error checking file: " + e.getMessage());
            } catch (InterruptedException e) {
                System.err.println("Monitoring interrupted: " + e.getMessage());
                break;
            }
        }
    }
    
    /**
     * Upload the file to the FTP server using the raw FTP protocol.
     */
    private void uploadFile() {
        Socket controlSocket = null;
        BufferedReader controlReader = null;
        PrintWriter controlWriter = null;
        
        try {
            // Establish control connection
            controlSocket = new Socket(ftpServer, ftpPort);
            controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            controlWriter = new PrintWriter(controlSocket.getOutputStream(), true);
            
            // Read welcome message
            String response = readResponse(controlReader);
            System.out.println("Server: " + response);
            
            // Send username
            controlWriter.println("USER " + ftpUsername);
            response = readResponse(controlReader);
            System.out.println("Server: " + response);
            
            // Send password
            controlWriter.println("PASS " + ftpPassword);
            response = readResponse(controlReader);
            System.out.println("Server: " + response);
            
            if (!response.startsWith("230")) {
                System.err.println("Failed to log in to FTP server");
                return;
            }
            
            // Change to binary mode
            controlWriter.println("TYPE I");
            response = readResponse(controlReader);
            System.out.println("Server: " + response);
            
            // Change to remote directory if specified
            if (remoteDirectory != null && !remoteDirectory.isEmpty()) {
                controlWriter.println("CWD " + remoteDirectory);
                response = readResponse(controlReader);
                System.out.println("Server: " + response);
            }
            
            // Extract filename from path
            String fileName = new File(filePath).getName();
            
            // Enter passive mode for data connection
            controlWriter.println("PASV");
            response = readResponse(controlReader);
            System.out.println("Server: " + response);
            
            // Parse passive mode response to get host and port for data connection
            int[] hostAndPort = parsePassiveModeResponse(response);
            String dataHost = hostAndPort[0] + "." + hostAndPort[1] + "." + hostAndPort[2] + "." + hostAndPort[3];
            int dataPort = (hostAndPort[4] * 256) + hostAndPort[5];
            
            // Establish data connection
            Socket dataSocket = new Socket(dataHost, dataPort);
            
            // Send STOR command
            controlWriter.println("STOR " + fileName);
            response = readResponse(controlReader);
            System.out.println("Server: " + response);
            
            if (!response.startsWith("150")) {
                System.err.println("Failed to initiate file transfer");
                dataSocket.close();
                return;
            }
            
            // Send file data
            try (OutputStream dataOut = dataSocket.getOutputStream();
                 FileInputStream fileIn = new FileInputStream(filePath)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                
                while ((bytesRead = fileIn.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, bytesRead);
                }
                
                dataOut.flush();
            }
            
            // Close data connection
            dataSocket.close();
            
            // Read transfer complete response
            response = readResponse(controlReader);
            System.out.println("Server: " + response);
            
            if (response.startsWith("226")) {
                System.out.println("File uploaded successfully");
            } else {
                System.err.println("File upload may have failed");
            }
            
            // Logout
            controlWriter.println("QUIT");
            response = readResponse(controlReader);
            System.out.println("Server: " + response);
            
        } catch (Exception e) {
            System.err.println("Error during FTP upload: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clean up resources
            try {
                if (controlReader != null) controlReader.close();
                if (controlWriter != null) controlWriter.close();
                if (controlSocket != null) controlSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing resources: " + e.getMessage());
            }
        }
    }
    
    /**
     * Helper method to read FTP server responses.
     * 
     * @param reader BufferedReader for reading from the control connection
     * @return String containing the server response
     * @throws IOException if an I/O error occurs
     */
    private String readResponse(BufferedReader reader) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;
        
        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
            
            // Check if this is the last line of the response
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
        }
        
        return response.toString().trim();
    }
    
    /**
     * Parse the PASV response to extract host and port information.
     * 
     * @param response PASV command response from the server
     * @return int array containing the 6 numbers representing host and port
     */
    private int[] parsePassiveModeResponse(String response) {
        // Extract numbers from the response in format: 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
        int start = response.indexOf('(');
        int end = response.indexOf(')');
        String[] parts = response.substring(start + 1, end).split(",");
        
        int[] result = new int[6];
        for (int i = 0; i < 6; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        
        return result;
    }
    
    /**
     * Main method to run the program.
     * 
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("HTML File Auto-Uploader");
        System.out.println("----------------------");
        
        // Get FTP server details
        System.out.print("FTP Server: ");
        String ftpServer = scanner.nextLine();
        
        System.out.print("FTP Port (default: 21): ");
        String portStr = scanner.nextLine();
        int ftpPort = portStr.isEmpty() ? 21 : Integer.parseInt(portStr);
        
        System.out.print("FTP Username: ");
        String ftpUsername = scanner.nextLine();
        
        System.out.print("FTP Password: ");
        String ftpPassword = scanner.nextLine();
        
        System.out.print("Remote Directory (leave empty for default): ");
        String remoteDirectory = scanner.nextLine();
        
        // Get file details
        System.out.print("Path to HTML file to monitor: ");
        String filePath = scanner.nextLine();
        
        System.out.print("Polling interval in seconds (default: 60): ");
        String intervalStr = scanner.nextLine();
        long pollingInterval = intervalStr.isEmpty() ? 60000 : Long.parseLong(intervalStr) * 1000;
        
        scanner.close();
        
        // Create and start the file monitor
        FileMonitorFTP monitor = new FileMonitorFTP(
            ftpServer, ftpPort, ftpUsername, ftpPassword,
            remoteDirectory, filePath, pollingInterval
        );
        
        monitor.startMonitoring();
    }
}