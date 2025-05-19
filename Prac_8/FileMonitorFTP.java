import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileMonitorFTP {
    private final String ftpServer;
    private final int ftpPort;
    private final String ftpUsername;
    private final String ftpPassword;
    private final String remoteDirectory;
    private final String filePath;
    private FileTime lastModifiedTime;
    private final long pollingInterval;

    private static final Pattern PASV_PATTERN = Pattern.compile("\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)");
    private static final Pattern EPSV_PATTERN = Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)");

    public FileMonitorFTP(String ftpServer, int ftpPort, String ftpUsername, String ftpPassword,
                          String remoteDirectory, String filePath, long pollingInterval) {
        this.ftpServer = ftpServer;
        this.ftpPort = ftpPort;
        this.ftpUsername = ftpUsername;
        this.ftpPassword = ftpPassword;
        this.remoteDirectory = (remoteDirectory == null || remoteDirectory.trim().isEmpty()) ? null : remoteDirectory.trim();
        this.filePath = filePath;
        this.pollingInterval = pollingInterval;

        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                System.err.println("Error: The file to monitor does not exist: " + filePath);
                System.exit(1);
            }
            this.lastModifiedTime = Files.getLastModifiedTime(path);
            System.out.println("Initial file modification time: " + this.lastModifiedTime);
        } catch (IOException e) {
            System.err.println("Error getting initial file modification time: " + e.getMessage());
            System.exit(1);
        }
    }

    public void startMonitoring() {
        System.out.println("Starting to monitor file: " + filePath);
        System.out.println("Polling every " + pollingInterval / 1000 + " seconds.");
        System.out.println("Press Ctrl+C to stop monitoring.");

        while (true) {
            try {
                Path path = Paths.get(filePath);
                if (!Files.exists(path)) {
                    System.err.println("Monitored file " + filePath + " no longer exists. Stopping monitor.");
                    break;
                }
                FileTime currentModifiedTime = Files.getLastModifiedTime(path);

                if (currentModifiedTime.compareTo(lastModifiedTime) > 0) {
                    System.out.println("\nFile changed at: " + currentModifiedTime);
                    System.out.println("Uploading file to FTP server...");

                    if (uploadFile()) {
                        lastModifiedTime = currentModifiedTime;
                        System.out.println("File successfully uploaded and timestamp updated.");
                    } else {
                        System.err.println("File upload failed. Will retry on next change or interval.");
                    }
                }
                Thread.sleep(pollingInterval);
            } catch (IOException e) {
                System.err.println("Error checking file: " + e.getMessage());
            } catch (InterruptedException e) {
                System.out.println("Monitoring interrupted. Exiting.");
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean uploadFile() {
        Socket controlSocket = null;
        BufferedReader controlReader = null;
        PrintWriter controlWriter = null;
        Socket dataSocket = null;
        String response = "";

        try {
            System.out.println("Connecting to FTP server: " + ftpServer + ":" + ftpPort);
            controlSocket = new Socket();
            controlSocket.connect(new InetSocketAddress(ftpServer, ftpPort), 10000);
            controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            controlWriter = new PrintWriter(controlSocket.getOutputStream(), true);

            response = readResponse(controlReader);
            System.out.println("S: " + response);
            if (!response.startsWith("220")) {
                System.err.println("FTP server did not send a ready message.");
                return false;
            }

            sendCommand(controlWriter, "USER " + ftpUsername);
            response = readResponse(controlReader);
            System.out.println("S: " + response);
            if (!response.startsWith("331")) {
                System.err.println("FTP username rejected or error: " + response);
                return false;
            }

            sendCommand(controlWriter, "PASS " + ftpPassword);
            response = readResponse(controlReader);
            System.out.println("S: " + response);
            if (!response.startsWith("230")) {
                System.err.println("FTP login failed: " + response);
                return false;
            }
            System.out.println("Successfully logged in as " + ftpUsername);

            sendCommand(controlWriter, "TYPE I");
            response = readResponse(controlReader);
            System.out.println("S: " + response);
            if (!response.startsWith("200")) {
                System.err.println("Failed to set TYPE I (Binary mode): " + response);
            }

            if (remoteDirectory != null && !remoteDirectory.isEmpty()) {
                sendCommand(controlWriter, "CWD " + remoteDirectory);
                response = readResponse(controlReader);
                System.out.println("S: " + response);
                if (!response.startsWith("250")) {
                    System.err.println("Failed to change remote directory to '" + remoteDirectory + "': " + response);
                    sendCommand(controlWriter, "MKD " + remoteDirectory);
                    response = readResponse(controlReader);
                    System.out.println("S: " + response);
                    if (response.startsWith("257")) {
                        System.out.println("Created remote directory: " + remoteDirectory);
                        sendCommand(controlWriter, "CWD " + remoteDirectory);
                        response = readResponse(controlReader);
                        System.out.println("S: " + response);
                        if (!response.startsWith("250")) {
                            System.err.println("Still failed to change to directory '" + remoteDirectory + "' after MKD.");
                            return false;
                        }
                    } else {
                         System.err.println("Failed to create directory '" + remoteDirectory + "': " + response);
                         return false;
                    }
                }
                 System.out.println("Changed to remote directory: " + remoteDirectory);
            }

            String dataHost;
            int dataPort;

            sendCommand(controlWriter, "EPSV");
            response = readResponse(controlReader);
            System.out.println("S: " + response);

            Matcher epsvMatcher = EPSV_PATTERN.matcher(response);
            if (response.startsWith("229") && epsvMatcher.find()) {
                dataHost = ftpServer;
                dataPort = Integer.parseInt(epsvMatcher.group(1));
                System.out.println("EPSV mode successful. Data port: " + dataPort);
            } else {
                System.out.println("EPSV failed or not supported (" + response.substring(0, Math.min(response.length(), 3)) + "), trying PASV...");
                sendCommand(controlWriter, "PASV");
                response = readResponse(controlReader);
                System.out.println("S: " + response);
                if (!response.startsWith("227")) {
                    System.err.println("PASV command failed: " + response);
                    return false;
                }
                Matcher pasvMatcher = PASV_PATTERN.matcher(response);
                if (!pasvMatcher.find()) {
                    System.err.println("Could not parse PASV response: " + response);
                    return false;
                }
                String ipPart1 = pasvMatcher.group(1);
                String ipPart2 = pasvMatcher.group(2);
                String ipPart3 = pasvMatcher.group(3);
                String ipPart4 = pasvMatcher.group(4);
                dataHost = ipPart1 + "." + ipPart2 + "." + ipPart3 + "." + ipPart4;
                if ("0.0.0.0".equals(dataHost)) {
                    dataHost = ftpServer;
                    System.out.println("PASV response was 0.0.0.0, using control connection IP: " + dataHost);
                }
                dataPort = (Integer.parseInt(pasvMatcher.group(5)) * 256) + Integer.parseInt(pasvMatcher.group(6));
                System.out.println("PASV mode successful. Data host: " + dataHost + ", Data port: " + dataPort);
            }

            System.out.println("Connecting to data port: " + dataHost + ":" + dataPort);
            dataSocket = new Socket();
            dataSocket.connect(new InetSocketAddress(dataHost, dataPort), 10000);

            String fileName = Paths.get(filePath).getFileName().toString();
            sendCommand(controlWriter, "STOR " + fileName);
            response = readResponse(controlReader);
            System.out.println("S: " + response);
            if (!(response.startsWith("150") || response.startsWith("125"))) {
                System.err.println("FTP server did not accept STOR command: " + response);
                if (dataSocket != null) dataSocket.close();
                return false;
            }
            System.out.println("Starting file data transfer for: " + fileName);

            try (OutputStream dataOut = dataSocket.getOutputStream();
                 FileInputStream fileIn = new FileInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesTransferred = 0;
                long fileSize = Files.size(Paths.get(filePath));

                while ((bytesRead = fileIn.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, bytesRead);
                    totalBytesTransferred += bytesRead;
                    System.out.print("\rTransferred: " + totalBytesTransferred + "/" + fileSize + " bytes (" + (fileSize > 0 ? (totalBytesTransferred * 100 / fileSize) : 100) + "%)");
                }
                dataOut.flush();
                System.out.println("\nFile data sent.");
            } finally {
                 if (dataSocket != null && !dataSocket.isClosed()) {
                    dataSocket.close();
                 }
            }

            response = readResponse(controlReader);
            System.out.println("S: " + response);
            if (!response.startsWith("226")) {
                System.err.println("File transfer may not have completed successfully: " + response);
                if (!response.startsWith("2")) {
                     return false;
                }
            }

            System.out.println("File '" + fileName + "' uploaded successfully to " + (remoteDirectory != null ? remoteDirectory : "default directory") + ".");
            return true;

        } catch (UnknownHostException e) {
            System.err.println("Error: Unknown FTP server host: " + ftpServer + " - " + e.getMessage());
            return false;
        } catch (SocketTimeoutException e) {
            System.err.println("Error: Timeout connecting to FTP server or data port: " + e.getMessage());
            return false;
        }
        catch (IOException e) {
            System.err.println("Error during FTP upload: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (controlWriter != null && controlSocket != null && !controlSocket.isClosed() && controlSocket.isConnected()) {
                    if (!isResponseCode(response, "221") && !isResponseCode(response, "421")) {
                         sendCommand(controlWriter, "QUIT");
                         String quitResponse = readResponse(controlReader);
                         System.out.println("S: " + quitResponse);
                         if (quitResponse.startsWith("221") || quitResponse.startsWith("226")) {
                             System.out.println("Logged out successfully.");
                         } else {
                             System.out.println("Logout response: " + quitResponse.trim());
                         }
                    }
                }
            } catch (IOException e) {
                System.err.println("Error sending QUIT or reading response: " + e.getMessage());
            } finally {
                try {
                    if (controlReader != null) controlReader.close();
                } catch (IOException e) {
                    System.err.println("Error closing control reader: " + e.getMessage());
                }
                if (controlWriter != null) controlWriter.close();
                try {
                    if (controlSocket != null && !controlSocket.isClosed()) controlSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing control socket: " + e.getMessage());
                }
                try {
                    if (dataSocket != null && !dataSocket.isClosed()) dataSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing data socket: " + e.getMessage());
                }
            }
        }
    }

    private boolean isResponseCode(String response, String code) {
        return response != null && response.startsWith(code);
    }

    private void sendCommand(PrintWriter writer, String command) {
        System.out.println("C: " + command);
        writer.println(command);
    }

    private String readResponse(BufferedReader reader) throws IOException {
        StringBuilder responseBuilder = new StringBuilder();
        String line;
        String responseCode = null;

        while ((line = reader.readLine()) != null) {
            responseBuilder.append(line).append("\r\n");
            if (line.length() >= 3) {
                if (responseCode == null) {
                    if (line.matches("^\\d{3}.*")) {
                        responseCode = line.substring(0, 3);
                    } else {
                         if (responseBuilder.toString().trim().isEmpty()) continue;
                         else break;
                    }
                }
                if (line.startsWith(responseCode + " ")) {
                    break;
                }
            } else {
                 if (responseBuilder.toString().trim().isEmpty()) continue;
                 else break;
            }
        }
        String finalResponse = responseBuilder.toString().trim();
        if (finalResponse.isEmpty() && line == null) {
            throw new IOException("FTP server closed connection unexpectedly (EOF reached while reading response).");
        }
        return finalResponse;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("HTML File Auto-Uploader");
        System.out.println("----------------------");
        System.out.println("This program monitors an HTML file for changes and uploads it to an FTP server.");

        String ftpServerInput;
        do {
            System.out.print("FTP Server address (e.g., ftp.example.com): ");
            ftpServerInput = scanner.nextLine().trim();
            if (ftpServerInput.isEmpty()) {
                System.out.println("FTP Server address cannot be empty.");
            }
        } while (ftpServerInput.isEmpty());

        System.out.print("FTP Port (default: 21, press Enter for default): ");
        String portStr = scanner.nextLine().trim();
        int ftpPort = portStr.isEmpty() ? 21 : Integer.parseInt(portStr);

        String ftpUsernameInput;
        do {
            System.out.print("FTP Username: ");
            ftpUsernameInput = scanner.nextLine().trim();
            if (ftpUsernameInput.isEmpty()) {
                 System.out.println("FTP Username is typically required. If anonymous, some servers use 'anonymous' or 'ftp'.");
            }
        } while (ftpUsernameInput.isEmpty());


        System.out.print("FTP Password: ");
        String ftpPasswordInput = scanner.nextLine();

        System.out.print("Remote Directory on FTP server (e.g., /public_html/myfiles, leave empty for root/default): ");
        String remoteDirectoryInput = scanner.nextLine().trim();

        String filePathInput;
        Path localFilePath;
        do {
            System.out.print("Path to the local HTML file to monitor (e.g., C:\\Users\\You\\Documents\\index.html): ");
            filePathInput = scanner.nextLine().trim();
            if (filePathInput.isEmpty()) {
                System.out.println("File path cannot be empty.");
                continue;
            }
            localFilePath = Paths.get(filePathInput);
            if (!Files.exists(localFilePath)) {
                System.out.println("Error: The specified file does not exist. Please provide a valid path.");
                filePathInput = "";
            } else if (Files.isDirectory(localFilePath)) {
                System.out.println("Error: The specified path is a directory, not a file. Please provide a path to a file.");
                filePathInput = "";
            }
        } while (filePathInput.isEmpty());


        System.out.print("Polling interval in seconds (default: 10, press Enter for default): ");
        String intervalStr = scanner.nextLine().trim();
        long pollingIntervalMs = intervalStr.isEmpty() ? 10000 : Long.parseLong(intervalStr) * 1000;
        if (pollingIntervalMs < 1000) {
            System.out.println("Polling interval too short, setting to 1 second.");
            pollingIntervalMs = 1000;
        }

        System.out.println("\nConfiguration Summary:");
        System.out.println("----------------------");
        System.out.println("FTP Server: " + ftpServerInput + ":" + ftpPort);
        System.out.println("FTP Username: " + ftpUsernameInput);
        System.out.println("Remote Directory: " + (remoteDirectoryInput.isEmpty() ? "Default" : remoteDirectoryInput));
        System.out.println("File to Monitor: " + filePathInput);
        System.out.println("Polling Interval: " + pollingIntervalMs / 1000 + " seconds");
        System.out.println("----------------------");


        FileMonitorFTP monitor = new FileMonitorFTP(
                ftpServerInput, ftpPort, ftpUsernameInput, ftpPasswordInput,
                remoteDirectoryInput, filePathInput, pollingIntervalMs
        );

        monitor.startMonitoring();
        scanner.close();
    }
}
