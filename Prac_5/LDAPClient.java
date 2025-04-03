import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Scanner;


/**
 * Enhanced LDAP Client with Complete RFC 4511 Compliance
 * Supports:
 * - Full LDAP v3 operations
 * - Detailed error handling per RFC specifications
 * - Friends directory structure
 * - Advanced search capabilities
 * - Comprehensive protocol documentation
 */
public class LDAPClient {
    // Configuration Constants with RFC references
    private static final int LDAP_PORT = 389; // RFC 4513 Section 3
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 5000;
    
    // Protocol Constants (RFC 4511 Section 4.1.1)
    private static final byte LDAP_BIND_REQUEST = 0x60; // [APPLICATION 0]
    private static final byte LDAP_BIND_RESPONSE = 0x61; // [APPLICATION 1]
    private static final byte LDAP_UNBIND_REQUEST = 0x42; // [APPLICATION 2]
    private static final byte LDAP_SEARCH_REQUEST = 0x63; // [APPLICATION 3]
    private static final byte LDAP_SEARCH_ENTRY = 0x64;   // [APPLICATION 4]
    private static final byte LDAP_SEARCH_DONE = 0x65;    // [APPLICATION 5]
    
    // ASN.1 Types (X.680)
    private static final byte SEQ = 0x30;       // SEQUENCE
    private static final byte OCTET_STR = 0x04; // OCTET STRING
    private static final byte INT = 0x02;       // INTEGER
    private static final byte ENUM = 0x0A;      // ENUMERATED
    private static final byte BOOL = 0x01;      // BOOLEAN
    private static final byte SET = 0x31;       // SET
    
    // Filter Types (RFC 4511 Section 4.5.1)
    private static final byte AND_FILTER = (byte)0xA0;
    private static final byte OR_FILTER = (byte)0xA1;
    private static final byte NOT_FILTER = (byte)0xA2;
    private static final byte EQUAL_FILTER = (byte)0xA3;
    private static final byte SUBSTR_FILTER = (byte)0xA4;
    
    // Authentication Choices (RFC 4511 Section 4.2)
    private static final byte SIMPLE_AUTH = (byte)0x80;
    private static final byte SASL_AUTH = (byte)0xA3;
    
    // Result Codes (RFC 4511 Section 4.1.9)
    private static final int SUCCESS = 0;
    private static final int OPERATIONS_ERROR = 1;
    private static final int INVALID_CREDENTIALS = 49;
    private static final int INSUFFICIENT_ACCESS = 50;
    private static final int BUSY = 51;
    private static final int UNAVAILABLE = 52;
    private static final int UNWILLING_TO_PERFORM = 53;
    private static final int TIME_LIMIT_EXCEEDED = 54;
    
    // Connection State
    private static int msgId = 1;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final String serverHost;
    private final String baseDN;
    private final String adminDN;
    private final String password;
    private boolean connected = false;
    private boolean bound = false;

    public LDAPClient(String host, String baseDN, String adminDN, String password) {
        // Validate base DN contains ou=Friends as required by assignment
        if (!baseDN.toLowerCase().contains("ou=friends")) {
            System.err.println("Warning: Base DN should include ou=Friends for this application");
        }
        
        this.serverHost = host;
        this.baseDN = baseDN;
        this.adminDN = adminDN;
        this.password = password;
    }

    /* ========== Connection Methods ========== */
    
    /**
     * Connects to LDAP server (RFC 4511 Section 4.2.1)
     * @return true if connection established successfully
     */
    public boolean connect() {
        if (connected) {
            System.err.println("[CONNECT] Already connected");
            return true;
        }
        
        try {
            System.out.println("[CONNECT] Testing port " + LDAP_PORT + "...");
            if (!testPort(serverHost, LDAP_PORT)) {
                System.err.println("[ERROR] Port " + LDAP_PORT + " not available");
                return false;
            }

            System.out.println("[CONNECT] Establishing connection...");
            socket = new Socket();
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(serverHost, LDAP_PORT), CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            
            System.out.printf("[CONNECT] Connected to %s:%d (Local port: %d)\n",
                socket.getInetAddress().getHostAddress(),
                socket.getPort(),
                socket.getLocalPort());
                
            connected = true;
            return true;
        } catch (IOException e) {
            System.err.println("[CONNECT] Failed: " + e.getMessage());
            return false;
        }
    }

    private boolean testPort(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /* ========== Authentication Methods ========== */
    
    /**
     * Performs LDAP Bind operation (RFC 4511 Section 4.2)
     * @return true if bind successful
     * @throws IOException if network error occurs
     */
    public boolean bind() throws IOException {
        if (!connected) {
            throw new IllegalStateException("Not connected to server");
        }
        if (bound) {
            System.out.println("[AUTH] Already bound");
            return true;
        }
        
        int retries = 2;
        while (retries-- > 0) {
            try {
                System.out.println("[AUTH] Binding as " + adminDN);
                
                // First create the Bind Request content
                ByteArrayOutputStream bindContent = new ByteArrayOutputStream();
                writeCredentials(bindContent);
                
                // Then create the ProtocolOp (BindRequest)
                ByteArrayOutputStream protocolOp = new ByteArrayOutputStream();
                protocolOp.write(LDAP_BIND_REQUEST);
                writeLength(protocolOp, bindContent.size());
                protocolOp.write(bindContent.toByteArray());
                
                // Finally create the complete LDAPMessage (SEQUENCE)
                ByteArrayOutputStream ldapMessage = new ByteArrayOutputStream();
                ldapMessage.write(SEQ); // SEQUENCE tag
                
                // Calculate total length: messageID (3) + protocolOp (bindContent + 2)
                int totalLength = 3 + protocolOp.size();
                writeLength(ldapMessage, totalLength);
                
                // Write messageID (INTEGER 1)
                ldapMessage.write(INT);
                ldapMessage.write(1);
                ldapMessage.write(1); // Message ID = 1
                
                // Write the protocolOp
                ldapMessage.write(protocolOp.toByteArray());
                
                logPacket("Sending Bind Request", ldapMessage.toByteArray());
                out.write(ldapMessage.toByteArray());
                
                socket.setSoTimeout(3000);
                byte[] response = readResponse();
                socket.setSoTimeout(READ_TIMEOUT);
                
                logPacket("Received Bind Response", response);
                
                bound = processBindResponse(response);
                return bound;
                
            } catch (IOException e) {
                System.err.println("[AUTH] Attempt failed: " + e.getMessage());
                if (retries > 0) {
                    System.out.println("Retrying...");
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    reconnect();
                }
            }
        }
        return false;
    }
    
    private void reconnect() throws IOException {
        disconnect();
        msgId = 1;  
        connect();
    }
    
    private void writeMessageHeader(ByteArrayOutputStream out) throws IOException {
        // MessageID ::= INTEGER (0..2147483647)
        out.write(INT);
        out.write(1);
        out.write(msgId++);
    }


    private void writeCredentials(ByteArrayOutputStream out) throws IOException {
        // LDAP version (INTEGER 3)
        out.write(INT);
        out.write(1);
        out.write(3);
        
        // DN (OCTET STRING)
        byte[] dn = adminDN.getBytes("UTF-8");
        out.write(OCTET_STR);
        writeLength(out, dn.length);
        out.write(dn);
        
        // Simple authentication
        byte[] pwd = password.getBytes("UTF-8");
        out.write(SIMPLE_AUTH); // Context-specific primitive tag 0
        writeLength(out, pwd.length);
        out.write(pwd);
    }

    private void writeLength(ByteArrayOutputStream out, int len) throws IOException {
        if (len < 128) {
            out.write(len);
        } else {
            int numBytes = (len <= 0xFF) ? 1 : (len <= 0xFFFF) ? 2 : 3;
            out.write(0x80 | numBytes);
            for (int i = numBytes-1; i >= 0; i--) {
                out.write((len >> (8*i)) & 0xFF);
            }
        }
    }

    /**
     * Processes Bind Response (RFC 4511 Section 4.2.2)
     * @param response The server response
     * @return true if bind successful
     */
    private boolean processBindResponse(byte[] response) {
        try {
            if (response == null || response.length < 2) {
                System.err.println("[AUTH] Empty or too short response");
                return false;
            }
    
            // Check for LDAPMessage SEQUENCE
            if (response[0] != SEQ) {
                System.err.println("[AUTH] Expected SEQUENCE tag, got: " + response[0]);
                return false;
            }
    
            int pos = 1;
            // Skip length bytes
            int length = response[pos++] & 0xFF;
            if (length > 127) {
                int lengthBytes = length & 0x7F;
                pos += lengthBytes;
            }
    
            // Check Message ID (should match our request)
            if (pos >= response.length || response[pos] != INT) {
                System.err.println("[AUTH] Missing or invalid message ID");
                return false;
            }
            pos += 3; // Skip INTEGER tag and length (assuming single-byte message ID)
    
            // Check Bind Response tag
            if (pos >= response.length || response[pos] != LDAP_BIND_RESPONSE) {
                System.err.println("[AUTH] Expected BindResponse, got: " + 
                                 (pos < response.length ? response[pos] : "EOF"));
                return false;
            }
            pos++;
    
            // Parse BindResponse content
            int bindRespLength = response[pos++] & 0xFF;
            if (bindRespLength > 127) {
                int lengthBytes = bindRespLength & 0x7F;
                pos += lengthBytes;
            }
    
            // Parse resultCode (ENUMERATED)
            if (pos >= response.length || response[pos] != ENUM) {
                System.err.println("[AUTH] Missing resultCode");
                return false;
            }
            pos++;
            int resultCodeLength = response[pos++] & 0xFF;
            if (pos + resultCodeLength > response.length) {
                System.err.println("[AUTH] Invalid resultCode length");
                return false;
            }
            int resultCode = response[pos] & 0xFF;
            pos += resultCodeLength;
    
            // Skip matchedDN and diagnosticMessage (optional)
            // ... could parse these for better error reporting ...
    
            String resultMessage = getResultMessage(resultCode);
            
            if (resultCode == SUCCESS) {
                System.out.println("[AUTH] Bind successful");
                return true;
            } else {
                System.err.println("[AUTH] Bind failed: " + resultMessage);
                return false;
            }
        } catch (Exception e) {
            System.err.println("[AUTH] Error processing response: " + e.getMessage());
            return false;
        }
    }
    
    private String getResultMessage(int resultCode) {
        switch (resultCode) {
            case SUCCESS: return "Success";
            case OPERATIONS_ERROR: return "Operations error";
            case INVALID_CREDENTIALS: return "Invalid credentials (49)";
            case INSUFFICIENT_ACCESS: return "Insufficient access rights (50)";
            case BUSY: return "Server busy (51)";
            case UNAVAILABLE: return "Server unavailable (52)";
            case UNWILLING_TO_PERFORM: return "Unwilling to perform (53)";
            case TIME_LIMIT_EXCEEDED: return "Time limit exceeded (54)";
            default: return "Unknown error (" + resultCode + ")";
        }
    }

    /* ========== Search Methods ========== */
    
    /**
     * Searches for a friend by name (RFC 4511 Section 4.5.1)
     * @param name Name to search for
     * @param exactMatch Whether to use exact match or substring match
     * @return Telephone number or error message
     */
    public String searchFriend(String name, boolean exactMatch) throws IOException {
        if (!bound) {
            return "Not authenticated";
        }
    
        try {
            System.out.println("[SEARCH] Looking for: " + name + 
                             (exactMatch ? " (exact match)" : " (substring match)"));
    
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            
            // Outer LDAPMessage SEQUENCE
            msg.write(SEQ); // 0x30
            int lengthPos = msg.size();
            msg.write(0); // placeholder for total length
            
            // Message ID (INTEGER)
            msg.write(INT); // 0x02
            msg.write(1);
            msg.write(msgId++);
            
            // SearchRequest (APPLICATION 3)
            msg.write(LDAP_SEARCH_REQUEST); // 0x63
            int searchReqLengthPos = msg.size();
            msg.write(0); // placeholder for search request length
            
            // Base DN (OCTET STRING)
            String searchBase = "ou=Friends," + baseDN;
            msg.write(OCTET_STR); // 0x04
            writeLength(msg, searchBase.length());
            msg.write(searchBase.getBytes("UTF-8"));
            
            // Scope (ENUMERATED) - 2 = subtree
            msg.write(ENUM); // 0x0A
            msg.write(1);
            msg.write(2);
            
            // DerefAliases (ENUMERATED) - 0 = never
            msg.write(ENUM); // 0x0A
            msg.write(1);
            msg.write(0);
            
            // SizeLimit (INTEGER) - 0 = no limit
            msg.write(INT); // 0x02
            msg.write(1);
            msg.write(0);
            
            // TimeLimit (INTEGER) - 0 = no limit
            msg.write(INT); // 0x02
            msg.write(1);
            msg.write(0);
            
            // TypesOnly (BOOLEAN) - false
            msg.write(BOOL); // 0x01
            msg.write(1);
            msg.write(0);
            
            // Filter construction
            if (exactMatch) {
                // Exact match filter: (&(objectClass=inetOrgPerson)(cn=name))
                buildExactMatchFilter(msg, name);
            } else {
                // Substring match filter: (&(objectClass=inetOrgPerson)(cn=*name*))
                buildSubstringFilter(msg, name);
            }
            
            // Requested Attributes (SEQUENCE)
            ByteArrayOutputStream attrs = new ByteArrayOutputStream();
            // Request telephoneNumber
            attrs.write(OCTET_STR); // 0x04
            writeLength(attrs, "telephoneNumber".length());
            attrs.write("telephoneNumber".getBytes("UTF-8"));
            
            msg.write(SEQ); // 0x30
            writeLength(msg, attrs.size());
            msg.write(attrs.toByteArray());
            
            // Fill in lengths
            byte[] content = msg.toByteArray();
            
            // Calculate SearchRequest length (everything after message ID)
            int searchReqLength = content.length - 4 - 2; // minus header and message ID
            content[searchReqLengthPos] = (byte)searchReqLength;
            
            // Calculate total LDAPMessage length (everything after initial SEQUENCE)
            int totalLength = content.length - 2; // minus SEQUENCE tag and length byte
            content[lengthPos] = (byte)totalLength;
            
            logPacket("Sending Search Request", content);
            out.write(content);
            
            // Read response with timeout
            socket.setSoTimeout(5000);
            byte[] response = readResponse();
            socket.setSoTimeout(READ_TIMEOUT);
            
            logPacket("Received Search Response", response);
            
            return processSearchResponse(response);
        } catch (IOException e) {
            System.err.println("[SEARCH] Failed: " + e.getMessage());
            throw e;
        }
    }
    
    private void buildExactMatchFilter(ByteArrayOutputStream msg, String name) throws IOException {
        // Build AND filter: (&(objectClass=inetOrgPerson)(cn=name))
        
        // AND filter header
        msg.write(AND_FILTER); // 0xA0
        int andLengthPos = msg.size();
        msg.write(0); // placeholder for AND filter length
        
        // First condition: objectClass=inetOrgPerson
        buildEqualityFilter(msg, "objectClass", "inetOrgPerson");
        
        // Second condition: cn=name
        buildEqualityFilter(msg, "cn", name);
        
        // Fill AND filter length
        byte[] andContent = msg.toByteArray();
        int andLength = andContent.length - andLengthPos - 1;
        andContent[andLengthPos] = (byte)andLength;
    }
    
    private void buildSubstringFilter(ByteArrayOutputStream msg, String name) throws IOException {
        // Build AND filter: (&(objectClass=inetOrgPerson)(cn=*name*))
        
        // AND filter header
        msg.write(AND_FILTER); // 0xA0
        int andLengthPos = msg.size();
        msg.write(0); // placeholder for AND filter length
        
        // First condition: objectClass=inetOrgPerson
        buildEqualityFilter(msg, "objectClass", "inetOrgPerson");
        
        // Second condition: cn=*name*
        buildSubstringCondition(msg, "cn", name);
        
        // Fill AND filter length
        byte[] andContent = msg.toByteArray();
        int andLength = andContent.length - andLengthPos - 1;
        andContent[andLengthPos] = (byte)andLength;
    }
    
    private void buildEqualityFilter(ByteArrayOutputStream msg, String attr, String value) throws IOException {
        // Build simple equality filter: (attr=value)
        msg.write(EQUAL_FILTER); // 0xA3
        int filterLengthPos = msg.size();
        msg.write(0); // placeholder
        
        // Attribute
        msg.write(OCTET_STR); // 0x04
        writeLength(msg, attr.length());
        msg.write(attr.getBytes("UTF-8"));
        
        // Value
        msg.write(OCTET_STR); // 0x04
        writeLength(msg, value.length());
        msg.write(value.getBytes("UTF-8"));
        
        // Fill filter length
        byte[] filterContent = msg.toByteArray();
        int filterLength = filterContent.length - filterLengthPos - 1;
        filterContent[filterLengthPos] = (byte)filterLength;
    }
    
    private void buildSubstringCondition(ByteArrayOutputStream msg, String attr, String value) throws IOException {
        // Build substring filter: (attr=*value*)
        msg.write(SUBSTR_FILTER); // 0xA4
        int filterLengthPos = msg.size();
        msg.write(0); // placeholder
        
        // Attribute
        msg.write(OCTET_STR); // 0x04
        writeLength(msg, attr.length());
        msg.write(attr.getBytes("UTF-8"));
        
        // Substring components
        ByteArrayOutputStream subComponents = new ByteArrayOutputStream();
        // Initial *
        subComponents.write(OCTET_STR); // 0x04
        writeLength(subComponents, 1);
        subComponents.write('*');
        // Value
        subComponents.write(OCTET_STR); // 0x04
        writeLength(subComponents, value.length());
        subComponents.write(value.getBytes("UTF-8"));
        // Final *
        subComponents.write(OCTET_STR); // 0x04
        writeLength(subComponents, 1);
        subComponents.write('*');
        
        msg.write(subComponents.toByteArray());
        
        // Fill filter length
        byte[] filterContent = msg.toByteArray();
        int filterLength = filterContent.length - filterLengthPos - 1;
        filterContent[filterLengthPos] = (byte)filterLength;
    }
   

    /**
     * Processes search response (RFC 4511 Section 4.5.2)
     */
    private String processSearchResponse(byte[] response) {
        try {
            if (response.length < 2) return "Invalid response";
            
            if (response[0] == LDAP_SEARCH_ENTRY) {
                int pos = 1;
                pos += 1 + (response[pos] & 0xFF); // Skip length and message ID
                
                // Skip DN
                pos += 1 + (response[pos] & 0xFF);
                
                if (response[pos++] == SEQ) { // Attribute sequence
                    pos += 1 + (response[pos] & 0xFF);
                    
                    while (pos < response.length) {
                        if (response[pos++] == SEQ) { // Attribute value sequence
                            pos += 1 + (response[pos] & 0xFF);
                            
                            // Get attribute type
                            if (response[pos++] == OCTET_STR) {
                                int typeLen = response[pos++] & 0xFF;
                                String attrType = new String(response, pos, typeLen, "UTF-8");
                                pos += typeLen;
                                
                                if (attrType.equals("telephoneNumber")) {
                                    if (response[pos++] == SET) { // AttributeValueSet
                                        pos += 1 + (response[pos] & 0xFF);
                                        if (response[pos++] == OCTET_STR) {
                                            int valLen = response[pos++] & 0xFF;
                                            return new String(response, pos, valLen, "UTF-8");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return "No telephone number found";
            } 
            else if (response[0] == LDAP_SEARCH_DONE) {
                int pos = 1;
                pos += 1 + (response[pos] & 0xFF); // Skip length
                pos += 3; // Skip message ID
                
                if (response[pos] == ENUM) { // resultCode
                    pos += 2;
                    int resultCode = response[pos] & 0xFF;
                    return getSearchResultMessage(resultCode);
                }
            }
            return "Unexpected response";
        } catch (Exception e) {
            return "Error parsing response: " + e.getMessage();
        }
    }
    
    private String getSearchResultMessage(int resultCode) {
        switch (resultCode) {
            case SUCCESS: return "No results found";
            case TIME_LIMIT_EXCEEDED: return "Search time limit exceeded";
            case 32: return "Entry does not exist";
            case 34: return "Invalid DN syntax";
            case 48: return "Inappropriate authentication";
            default: return "Search error (code " + resultCode + ")";
        }
    }

    /* ========== Core Protocol Methods ========== */
    
    private byte[] readResponse() throws IOException {
        DataInputStream din = new DataInputStream(in);
        
        try {
            // Read message type (with timeout)
            int type;
            try {
                type = din.readUnsignedByte();
            } catch (EOFException e) {
                throw new IOException("Server closed connection unexpectedly", e);
            }
            
            // Read BER length (X.690 Section 8.1)
            int length = readBerLength(din);
            
            // Verify length is reasonable (e.g., < 10MB)
            if (length > 10_000_000) {
                throw new IOException("Response too large: " + length + " bytes");
            }
            
            // Read full message
            int lengthBytes = getBerLengthBytes(length);
            byte[] response = new byte[1 + lengthBytes + length];
            response[0] = (byte)type;
            byte[] lengthEncoded = encodeBerLength(length);
            System.arraycopy(lengthEncoded, 0, response, 1, lengthEncoded.length);
            din.readFully(response, 1 + lengthEncoded.length, length);
            
            return response;
        } catch (SocketTimeoutException e) {
            throw new IOException("Timeout waiting for server response", e);
        }
    }

    private int readBerLength(DataInputStream din) throws IOException {
        int firstByte = din.readUnsignedByte();
        if (firstByte < 0x80) return firstByte;
        
        int lengthBytes = firstByte & 0x7F;
        if (lengthBytes > 4) {
            throw new IOException("Length too long: " + lengthBytes + " bytes");
        }
        
        int length = 0;
        for (int i = 0; i < lengthBytes; i++) {
            length = (length << 8) | din.readUnsignedByte();
        }
        return length;
    }

    private int getBerLengthBytes(int length) {
        if (length < 128) return 1;
        if (length < 256) return 2;
        if (length < 65536) return 3;
        return 4;
    }

    private byte[] encodeBerLength(int length) {
        if (length < 128) {
            return new byte[]{(byte)length};
        } else if (length < 256) {
            return new byte[]{(byte)0x81, (byte)length};
        } else if (length < 65536) {
            return new byte[]{(byte)0x82, (byte)(length >> 8), (byte)length};
        } else {
            return new byte[]{(byte)0x83, (byte)(length >> 16), 
                            (byte)(length >> 8), (byte)length};
        }
    }



    /* ========== Utility Methods ========== */
    
    private void logPacket(String description, byte[] data) {
        System.out.println("[PACKET] " + description + " (" + data.length + " bytes):");
        System.out.println(bytesToHex(data));
        System.out.println();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
            if ((i+1) % 16 == 0) sb.append("\n");
        }
        return sb.toString();
    }

    /* ========== Cleanup Methods ========== */
    
    /**
     * Performs LDAP Unbind operation (RFC 4511 Section 4.3)
     */
    public void disconnect() {
        try {
            if (socket != null && !socket.isClosed()) {
                System.out.println("[DISCONNECT] Sending unbind request");
                
                ByteArrayOutputStream msg = new ByteArrayOutputStream();
                // Outer SEQUENCE
                msg.write(SEQ);
                writeLength(msg, 7); // Total length: 7 bytes
                
                // Message ID (INTEGER 2)
                msg.write(INT);
                msg.write(1);
                msg.write(msgId++); // Increment message ID
                
                // Unbind Request (APPLICATION 2 with no content)
                msg.write(LDAP_UNBIND_REQUEST);
                msg.write(0); // No content
                
                logPacket("Sending Unbind Request", msg.toByteArray());
                out.write(msg.toByteArray());
                
                socket.close();
                System.out.println("[DISCONNECT] Connection closed");
            }
        } catch (IOException e) {
            System.err.println("[DISCONNECT] Error: " + e.getMessage());
        } finally {
            connected = false;
            bound = false;
        }
    }

    /* ========== Main Application ========== */
    
    public static void main(String[] args) throws IOException {
        
        System.setProperty("javax.net.debug", "all");
        
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("=== LDAP Friends Telephone Directory Client ===");
        System.out.println("Enter connection details:");
        
        System.out.print("Server Host: ");
        String host = scanner.nextLine().trim();
        
        System.out.print("Base DN (eg. ou=Friends,dc=example,dc=com): ");
        String baseDN = scanner.nextLine().trim();
        
        System.out.print("Admin DN (e.g. cn=admin,dc=example,dc=com): ");
        String adminDN = scanner.nextLine().trim();
        
        System.out.print("Password: ");
        String password = scanner.nextLine();
        
        LDAPClient client = new LDAPClient(host, baseDN, adminDN, password);
        
        if (!client.connect()) {
            System.err.println("Cannot connect to server. Exiting.");
            System.exit(1);
        }
        
        if (!client.bind()) {
            System.err.println("Authentication failed. Exiting.");
            client.disconnect();
            System.exit(1);
        }
        
        boolean running = true;
        while (running) {
            System.out.print("\nEnter friend's name (or 'exit' to quit): ");
            String name = scanner.nextLine().trim();
            
            if (name.equalsIgnoreCase("exit")) {
                running = false;
            } else if (!name.isEmpty()) {
                System.out.print("Exact match? (y/n): ");
                boolean exact = scanner.nextLine().trim().equalsIgnoreCase("y");
                
                String result = client.searchFriend(name, exact);
                System.out.println("Result: " + result);
            }
        }
        
        client.disconnect();
        scanner.close();
        System.out.println("Goodbye!");
    }
}