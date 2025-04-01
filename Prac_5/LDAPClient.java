import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * LDAPClient - A simple LDAP client for querying friend's telephone numbers
 * 
 * This client connects to an LDAP server, binds with credentials,
 * and allows searching for telephone numbers by friend name.
 * 
 * Note: This implementation manually constructs LDAP protocol messages
 * at the byte level without using LDAP libraries.
 */
public class LDAPClient {
    // LDAP Protocol Constants
    private static final int LDAP_PORT = 389;
    
    // LDAP Operation Codes
    private static final byte LDAP_BIND_REQUEST = 0x60;
    private static final byte LDAP_SEARCH_REQUEST = 0x63;
    private static final byte LDAP_UNBIND_REQUEST = 0x42;
    
    // ASN.1 Type Tags
    private static final byte SEQ = 0x30;        // SEQUENCE
    private static final byte OCTET_STR = 0x04;  // OCTET STRING
    private static final byte INT = 0x02;        // INTEGER
    private static final byte ENUM = 0x0A;       // ENUMERATED
    private static final byte BOOL = 0x01;       // BOOLEAN
    
    // LDAP Filter Types (Context Specific)
    private static final byte AND_FILTER = (byte) 0xA0;  // [0] AND
    private static final byte EQUAL_FILTER = (byte) 0xA3; // [3] EQUAL
    
    // LDAP Auth Types (Context Specific)
    private static final byte SIMPLE_AUTH = (byte) 0x80;  // [0] simple
    
    // Message tracking
    private static int msgId = 1;
    
    // Connection components
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    
    // Connection parameters
    private String serverHost;
    private String baseDN;
    private String adminDN;
    private String password;

    /**
     * Create a new LDAP client with the given parameters
     */
    public LDAPClient(String host, String baseDN, String adminDN, String password) {
        this.serverHost = host;
        this.baseDN = baseDN;
        this.adminDN = adminDN;
        this.password = password;
    }

    /**
     * Connect to the LDAP server
     */
    public boolean connect() {
        try {
            System.out.println("Connecting to LDAP server " + serverHost + ":" + LDAP_PORT + "...");
            socket = new Socket(serverHost, LDAP_PORT);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            System.out.println("Connected successfully");
            return true;
        } catch (IOException e) {
            System.err.println("Connection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Authenticate with the LDAP server using simple bind
     */
    public boolean bind() {
        try {
            System.out.println("Binding as " + adminDN + "...");
            
            // Create a new message buffer
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            
            // Add message ID
            msg.write(INT);
            msg.write(1);
            msg.write(msgId++);
            
            // Construct bind request content
            ByteArrayOutputStream bindContent = new ByteArrayOutputStream();
            
            // LDAP version (we use v3)
            bindContent.write(INT);
            bindContent.write(1);
            bindContent.write(3);
            
            // Bind DN
            byte[] dnBytes = adminDN.getBytes();
            bindContent.write(OCTET_STR);
            writeLength(bindContent, dnBytes.length);
            bindContent.write(dnBytes);
            
            // Simple authentication (password)
            byte[] pwdBytes = password.getBytes();
            bindContent.write(SIMPLE_AUTH);
            writeLength(bindContent, pwdBytes.length);
            bindContent.write(pwdBytes);
            
            // Wrap the bind content in the LDAP bind request envelope
            byte[] content = bindContent.toByteArray();
            msg.write(LDAP_BIND_REQUEST);
            writeLength(msg, content.length);
            msg.write(content);
            
            // Send the bind request to the server
            out.write(msg.toByteArray());
            
            // Read and process the response
            byte[] response = readResponse();
            
            // Check for successful bind (result code 0)
            if (response[0] == 0x61) { // Bind response
                int resultCode = response[7]; // Simplified navigation to result code
                if (resultCode == 0) {
                    System.out.println("Bind successful");
                    return true;
                } else {
                    System.out.println("Bind failed with code: " + resultCode);
                }
            }
            return false;
        } catch (IOException e) {
            System.err.println("Bind error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Search for a friend's telephone number by name
     */
    public String searchFriend(String name) {
        try {
            System.out.println("Searching for friend: " + name);
            
            // Create message buffer
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            
            // Message ID
            msg.write(INT);
            msg.write(1);
            msg.write(msgId++);
            
            // Construct search request content
            ByteArrayOutputStream searchContent = new ByteArrayOutputStream();
            
            // Base DN to search from
            byte[] baseDNBytes = baseDN.getBytes();
            searchContent.write(OCTET_STR);
            writeLength(searchContent, baseDNBytes.length);
            searchContent.write(baseDNBytes);
            
            // Search scope (2 = subtree)
            searchContent.write(ENUM);
            searchContent.write(1);
            searchContent.write(2);
            
            // Alias handling (3 = always deref)
            searchContent.write(ENUM);
            searchContent.write(1);
            searchContent.write(3);
            
            // Size limit (0 = no limit)
            searchContent.write(INT);
            searchContent.write(1);
            searchContent.write(0);
            
            // Time limit (0 = no limit)
            searchContent.write(INT);
            searchContent.write(1);
            searchContent.write(0);
            
            // Types Only (false = return attribute values)
            searchContent.write(BOOL);
            searchContent.write(1);
            searchContent.write(0);
            
            // Build search filter: (&(objectClass=person)(cn=friendName))
            ByteArrayOutputStream filter = new ByteArrayOutputStream();
            
            // Build the objectClass=person part
            ByteArrayOutputStream objectClassFilter = new ByteArrayOutputStream();
            byte[] objClassAttr = "objectClass".getBytes();
            byte[] personValue = "person".getBytes();
            
            objectClassFilter.write(OCTET_STR);
            writeLength(objectClassFilter, objClassAttr.length);
            objectClassFilter.write(objClassAttr);
            
            objectClassFilter.write(OCTET_STR);
            writeLength(objectClassFilter, personValue.length);
            objectClassFilter.write(personValue);
            
            byte[] ocFilter = objectClassFilter.toByteArray();
            
            // Build the cn=friendName part
            ByteArrayOutputStream nameFilter = new ByteArrayOutputStream();
            byte[] cnAttr = "cn".getBytes();
            byte[] nameValue = name.getBytes();
            
            nameFilter.write(OCTET_STR);
            writeLength(nameFilter, cnAttr.length);
            nameFilter.write(cnAttr);
            
            nameFilter.write(OCTET_STR);
            writeLength(nameFilter, nameValue.length);
            nameFilter.write(nameValue);
            
            byte[] cnFilter = nameFilter.toByteArray();
            
            // Combine the two filters with AND
            ByteArrayOutputStream andContent = new ByteArrayOutputStream();
            
            // Add objectClass filter
            andContent.write(EQUAL_FILTER);
            writeLength(andContent, ocFilter.length);
            andContent.write(ocFilter);
            
            // Add cn filter
            andContent.write(EQUAL_FILTER);
            writeLength(andContent, cnFilter.length);
            andContent.write(cnFilter);
            
            // Wrap in AND filter
            byte[] andData = andContent.toByteArray();
            filter.write(AND_FILTER);
            writeLength(filter, andData.length);
            filter.write(andData);
            
            // Add filter to search request
            filter.writeTo(searchContent);
            
            // Requested attributes (telephoneNumber)
            ByteArrayOutputStream attrs = new ByteArrayOutputStream();
            byte[] phoneAttr = "telephoneNumber".getBytes();
            attrs.write(OCTET_STR);
            writeLength(attrs, phoneAttr.length);
            attrs.write(phoneAttr);
            
            // Wrap attributes in a sequence
            byte[] attrList = attrs.toByteArray();
            searchContent.write(SEQ);
            writeLength(searchContent, attrList.length);
            searchContent.write(attrList);
            
            // Finalize the search request
            byte[] content = searchContent.toByteArray();
            msg.write(LDAP_SEARCH_REQUEST);
            writeLength(msg, content.length);
            msg.write(content);
            
            // Send the search request
            out.write(msg.toByteArray());
            
            // Get and process response
            byte[] response = readResponse();
            return extractPhoneNumber(response);
            
        } catch (IOException e) {
            System.err.println("Search error: " + e.getMessage());
            return "Error performing search";
        }
    }
    
    /**
     * Extract the telephone number from an LDAP response
     */
    private String extractPhoneNumber(byte[] response) {
        try {
            // Simple approach to find telephoneNumber in the response
            // This is a simplified implementation - in reality, you would need to
            // properly parse the ASN.1 structure of the response
            
            // Check if we got a search entry response
            if (response[0] == 0x64) {
                // Convert to string to make the search easier
                // (not efficient but works for this example)
                String respStr = new String(response);
                
                // Look for telephoneNumber attribute
                int phoneAttrPos = respStr.indexOf("telephoneNumber");
                if (phoneAttrPos != -1) {
                    // Find the value after the attribute name
                    int valueStart = respStr.indexOf(':', phoneAttrPos) + 1;
                    int valueEnd = respStr.indexOf('\n', valueStart);
                    if (valueEnd == -1) {
                        valueEnd = respStr.length();
                    }
                    
                    return respStr.substring(valueStart, valueEnd).trim();
                }
                return "No telephone number found";
                
            } else if (response[0] == 0x65) {
                // Search result done - check if we found anything
                int resultCode = response[7]; // Simplified position
                if (resultCode == 0) {
                    return "No matching friend found";
                } else {
                    return "Search failed (code " + resultCode + ")";
                }
            }
            
            return "Unexpected response from server";
        } catch (Exception e) {
            return "Error processing response: " + e.getMessage();
        }
    }

    /**
     * Cleanly disconnect from the LDAP server
     */
    public void disconnect() {
        try {
            System.out.println("Disconnecting from LDAP server...");
            
            // Create an unbind request (which has no content)
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            
            // Message ID
            msg.write(INT);
            msg.write(1);
            msg.write(msgId++);
            
            // Unbind request (no content)
            msg.write(LDAP_UNBIND_REQUEST);
            msg.write(0); // Zero length
            
            // Send the unbind request
            out.write(msg.toByteArray());
            
            // Close socket
            socket.close();
            System.out.println("Disconnected");
        } catch (IOException e) {
            System.err.println("Error during disconnect: " + e.getMessage());
        }
    }

    /**
     * Read a complete response from the LDAP server
     */
    private byte[] readResponse() throws IOException {
        // Simple approach: wait for data, then read it
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int bytesRead;
        
        // Wait for data to be available
        int attempts = 0;
        while (in.available() == 0 && attempts < 50) {
            try {
                Thread.sleep(100);
                attempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Read all available data
        while (in.available() > 0) {
            bytesRead = in.read(chunk);
            if (bytesRead > 0) {
                buffer.write(chunk, 0, bytesRead);
            }
            
            // Short pause to allow more data to arrive if needed
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        return buffer.toByteArray();
    }

    /**
     * Write a length value in BER encoding format
     */
    private void writeLength(ByteArrayOutputStream out, int length) throws IOException {
        if (length < 128) {
            // Short form
            out.write(length);
        } else {
            // Long form
            // Figure out how many bytes we need
            int bytes = 0;
            int temp = length;
            while (temp > 0) {
                bytes++;
                temp >>= 8;
            }
            
            // Write length of length field
            out.write(0x80 | bytes);
            
            // Write length bytes
            for (int i = bytes - 1; i >= 0; i--) {
                int shift = i * 8;
                out.write((length >> shift) & 0xFF);
            }
        }
    }

    /**
     * Main application entry point
     */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("==================================");
        System.out.println("   LDAP Friend Telephone Lookup   ");
        System.out.println("==================================");
        
        // Get connection details
        System.out.print("LDAP Server Host: ");
        String host = scanner.nextLine().trim();
        
        System.out.print("Base DN (e.g. ou=Friends,dc=example,dc=com): ");
        String baseDN = scanner.nextLine().trim();
        
        System.out.print("Admin DN (e.g. cn=admin,dc=example,dc=com): ");
        String adminDN = scanner.nextLine().trim();
        
        System.out.print("Password: ");
        String password = scanner.nextLine();
        
        // Create the client
        LDAPClient client = new LDAPClient(host, baseDN, adminDN, password);
        
        // Try to connect
        if (!client.connect()) {
            System.out.println("Failed to connect. Exiting.");
            scanner.close();
            return;
        }
        
        // Try to bind
        if (!client.bind()) {
            System.out.println("Authentication failed. Exiting.");
            scanner.close();
            return;
        }
        
        // Search loop
        boolean running = true;
        while (running) {
            System.out.println("\n----------------------------------");
            System.out.print("Enter friend name (or 'exit' to quit): ");
            String input = scanner.nextLine().trim();
            
            if (input.equalsIgnoreCase("exit")) {
                running = false;
            } else if (!input.isEmpty()) {
                String result = client.searchFriend(input);
                System.out.println("Result: " + result);
            }
        }
        
        // Clean up
        client.disconnect();
        scanner.close();
        System.out.println("Program terminated.");
    }
}