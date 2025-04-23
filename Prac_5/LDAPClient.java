import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;


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
    private static final byte LDAP_ADD_REQUEST = 0x68; // [APPLICATION 8]
private static final byte LDAP_ADD_RESPONSE = 0x69; // [APPLICATION 9]
    
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
    private final AtomicInteger currentMsgId = new AtomicInteger(1);

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

    while (in.available() > 0) {
        in.skip(in.available());
    }

    try {
        System.out.println("[SEARCH] Looking for: " + name + 
                         (exactMatch ? " (exact match)" : " (substring match)"));

        // Build the filter first
        ByteArrayOutputStream filter = new ByteArrayOutputStream();
        if (exactMatch) {
            buildExactMatchFilter(filter, name);
        } else {
            buildSubstringFilter(filter,"cn", name);
        }
        byte[] filterBytes = filter.toByteArray();

        // Now build the complete search request
        ByteArrayOutputStream searchRequest = new ByteArrayOutputStream();
        
        // Base DN
        byte[] baseDnBytes = baseDN.getBytes("UTF-8");
        searchRequest.write(OCTET_STR);
        writeLength(searchRequest, baseDnBytes.length);
        searchRequest.write(baseDnBytes);
        
        // Scope (singleLevel)
        searchRequest.write(ENUM);
        searchRequest.write(1);
        searchRequest.write(1);
        
        // DerefAliases (never)
        searchRequest.write(ENUM);
        searchRequest.write(1);
        searchRequest.write(0);
        
        // SizeLimit (1)
        searchRequest.write(INT);
        searchRequest.write(1);
        searchRequest.write(1);
        
        // TimeLimit (0)
        searchRequest.write(INT);
        searchRequest.write(1);
        searchRequest.write(0);
        
        // TypesOnly (false)
        searchRequest.write(BOOL);
        searchRequest.write(1);
        searchRequest.write(0);
        
        // Filter
        searchRequest.write(filterBytes);
        
        // Attributes (telephoneNumber)
        ByteArrayOutputStream attributes = new ByteArrayOutputStream();
        String[] requestedAttrs = {"telephoneNumber"};
        for (String attr : requestedAttrs) {
            attributes.write(OCTET_STR);
            writeLength(attributes, attr.length());
            attributes.write(attr.getBytes("UTF-8"));
        }
        
        searchRequest.write(SEQ);
        writeLength(searchRequest, attributes.size());
        searchRequest.write(attributes.toByteArray());
        
        // Now build the complete LDAP message
        ByteArrayOutputStream ldapMessage = new ByteArrayOutputStream();
        ldapMessage.write(SEQ); // Outer SEQUENCE
        
        // Calculate total length: messageID (3) + searchRequest (tag + length + content)
        int totalLength = 3 + 1 + getLengthBytes(searchRequest.size()) + searchRequest.size();
        writeLength(ldapMessage, totalLength);
        
        // Message ID
        // In searchFriend():
        ldapMessage.write(INT);
        ldapMessage.write(1);
        ldapMessage.write(currentMsgId.get()); // Use tracked ID

        int expectedMsgId = currentMsgId.getAndIncrement();
        
        // SearchRequest (APPLICATION 3)
        ldapMessage.write(LDAP_SEARCH_REQUEST);
        writeLength(ldapMessage, searchRequest.size());
        ldapMessage.write(searchRequest.toByteArray());
        
        byte[] packet = ldapMessage.toByteArray();
        logPacket("Sending Search Request", packet);
        out.write(packet);
        
        // Read response
        socket.setSoTimeout(5000);
        byte[] response = readResponse();
        socket.setSoTimeout(READ_TIMEOUT);
        
        logPacket("Received Search Response", response);
        
    
    
        byte[] response1 = readFullResponse();
    
        // Verify message ID first
        ByteArrayInputStream bis = new ByteArrayInputStream(response1);
        DataInputStream dis = new DataInputStream(bis);
        dis.readByte(); // SEQUENCE
        readBerLength(dis);
        dis.readByte(); // INTEGER
        int msgIdLength = dis.readByte();
        int receivedMsgId = dis.readByte();
        
        if (receivedMsgId != expectedMsgId) {
            return "No results found (sync error)";
        }
        
        return processSearchResponse(response);
    } catch (IOException e) {
        System.err.println("[SEARCH] Failed: " + e.getMessage());
        throw e;
    }
}

private void buildExactMatchFilter(ByteArrayOutputStream out, String name) throws IOException {
    // Build (&(objectClass=inetOrgPerson)(cn=name))
    
    // AND filter header (0xA0)
    out.write(AND_FILTER);
    int andLengthPos = out.size();
    out.write(0); // placeholder
    
    // First condition: objectClass=inetOrgPerson
    buildEqualityFilter(out, "objectClass", "inetOrgPerson");
    
    // Second condition: cn=name
    buildEqualityFilter(out, "cn", name );
    
    // Update AND filter length
    int andLength = out.size() - andLengthPos - 1;
    byte[] content = out.toByteArray();
    if (andLength > 127) {
        byte[] lenBytes = encodeBerLength(andLength);
        byte[] newContent = new byte[content.length + lenBytes.length - 1];
        System.arraycopy(content, 0, newContent, 0, andLengthPos);
        System.arraycopy(lenBytes, 0, newContent, andLengthPos, lenBytes.length);
        System.arraycopy(content, andLengthPos + 1, newContent, 
                      andLengthPos + lenBytes.length, content.length - (andLengthPos + 1));
        out.reset();
        out.write(newContent);
    } else {
        content[andLengthPos] = (byte)andLength;
        out.reset();
        out.write(content);
    }
}

private void buildEqualityFilter(ByteArrayOutputStream out, String attr, String value) throws IOException {
    // Build (attr=value)
    
    // Equality filter header (0xA3)
    out.write(EQUAL_FILTER);
    int filterLengthPos = out.size();
    out.write(0); // placeholder
    
    // Attribute
    out.write(OCTET_STR);
    writeLength(out, attr.length());
    out.write(attr.getBytes("UTF-8"));
    
    // Value
    out.write(OCTET_STR);
    writeLength(out, value.length());
    out.write(value.getBytes("UTF-8"));
    
    // Update filter length
    int filterLength = out.size() - filterLengthPos - 1;
    byte[] content = out.toByteArray();
    if (filterLength > 127) {
        byte[] lenBytes = encodeBerLength(filterLength);
        byte[] newContent = new byte[content.length + lenBytes.length - 1];
        System.arraycopy(content, 0, newContent, 0, filterLengthPos);
        System.arraycopy(lenBytes, 0, newContent, filterLengthPos, lenBytes.length);
        System.arraycopy(content, filterLengthPos + 1, newContent, 
                      filterLengthPos + lenBytes.length, content.length - (filterLengthPos + 1));
        out.reset();
        out.write(newContent);
    } else {
        content[filterLengthPos] = (byte)filterLength;
        out.reset();
        out.write(content);
    }
}



private void buildSubstringFilter(ByteArrayOutputStream out, String attr, String value) throws IOException {
    // This creates: (attr=*value*)
    
    // Substring filter header (0xA4)
    out.write(SUBSTR_FILTER);
    int lengthPos = out.size();
    out.write(0); // placeholder for length
    
    // Attribute type
    out.write(OCTET_STR);
    writeLength(out, attr.length());
    out.write(attr.getBytes("UTF-8"));
    
    // Substring components (SEQUENCE)
    out.write(SEQ);
    int seqLengthPos = out.size();
    out.write(0); // placeholder
    
    // Only one component: the value with wildcards
    out.write(OCTET_STR);
    writeLength(out, value.length());
    out.write(value.getBytes("UTF-8"));
    
    // Update sequence length
    byte[] content = out.toByteArray();
    int seqLength = content.length - seqLengthPos - 1;
    if (seqLength > 127) {
        byte[] lenBytes = encodeBerLength(seqLength);
        byte[] newContent = new byte[content.length + lenBytes.length - 1];
        System.arraycopy(content, 0, newContent, 0, seqLengthPos);
        System.arraycopy(lenBytes, 0, newContent, seqLengthPos, lenBytes.length);
        System.arraycopy(content, seqLengthPos + 1, newContent, 
                      seqLengthPos + lenBytes.length, content.length - seqLengthPos - 1);
        out.reset();
        out.write(newContent);
    } else {
        content[seqLengthPos] = (byte)seqLength;
        out.reset();
        out.write(content);
    }
    
    // Update filter length
    int filterLength = out.size() - lengthPos - 1;
    if (filterLength > 127) {
        byte[] lenBytes = encodeBerLength(filterLength);
        byte[] newContent = new byte[content.length + lenBytes.length - 1];
        System.arraycopy(content, 0, newContent, 0, lengthPos);
        System.arraycopy(lenBytes, 0, newContent, lengthPos, lenBytes.length);
        System.arraycopy(content, lengthPos + 1, newContent, 
                      lengthPos + lenBytes.length, content.length - lengthPos - 1);
        out.reset();
        out.write(newContent);
    } else {
        content[lengthPos] = (byte)filterLength;
        out.reset();
        out.write(content);
    }
}

/**
 * Processes search response (RFC 4511 Section 4.5.2)
 */
private String processSearchResponse(byte[] response) {
    try {

        ByteArrayInputStream bis = new ByteArrayInputStream(response);
        DataInputStream dis = new DataInputStream(bis);

        // Read LDAPMessage header
        dis.readByte(); // SEQUENCE
        readBerLength(dis); 
        dis.readByte(); // INTEGER (message ID tag)
        

        String responseStr = new String(response, "UTF-8");
        
        // Check for presence of "cn=" and "telephoneNumber"
        if (!responseStr.contains("cn=")) {
            return "No results found";
        }
        
        // Extract name between "cn=" and next comma
        int cnStart = responseStr.indexOf("cn=") + 3;
        int cnEnd = responseStr.indexOf(',', cnStart);
        String name = (cnEnd > 0) ? responseStr.substring(cnStart, cnEnd) 
                                 : responseStr.substring(cnStart);
        
        // Extract phone number after "telephoneNumber"
        String phone = "Not available";
        int phoneStart = responseStr.indexOf("telephoneNumber");
        if (phoneStart > 0) {
            phone = responseStr.substring(phoneStart + 15)
                   .replaceAll("[^0-9]", "").trim();
        }
        
        return "Found: " + name + "\nPhone: " + phone;
    } catch (Exception e) {
        return "Error processing response: " + e.getMessage();
    }
}

/**
 * Calculates the number of bytes needed to encode a length in BER format
 * @param length The length value to encode
 * @return Number of bytes needed (1-4)
 */
private int getLengthBytes(int length) {
    if (length < 128) {
        return 1;  // Short form - single byte
    } else if (length <= 0xFF) {
        return 2;  // 1 length byte + 1 value byte
    } else if (length <= 0xFFFF) {
        return 3;  // 1 length byte + 2 value bytes
    } else {
        return 4;  // 1 length byte + 3 value bytes
    }
}

private byte[] readFullResponse() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] temp = new byte[1024];
    
    // Read initial data
    int bytesRead = in.read(temp);
    buffer.write(temp, 0, bytesRead);
    
    // Check if we have a complete LDAP message
    while (!isCompleteMessage(buffer.toByteArray())) {
        bytesRead = in.read(temp);
        if (bytesRead == -1) break;
        buffer.write(temp, 0, bytesRead);
    }
    
    return buffer.toByteArray();
}
private boolean isCompleteMessage(byte[] data) {
    if (data.length < 2) return false;
    try {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        dis.readByte(); // SEQUENCE
        int length = readBerLength(dis);
        return data.length >= length + 2; // +2 for tag and length bytes
    } catch (IOException e) {
        return false;
    }
}



    /* ========== Core Protocol Methods ========== */

    /**
     * Logs the packet data for debugging purposes.
     * @param description Description of the packet
     * @param data The packet data as a byte array
     */
    private void logPacket(String description, byte[] data) {
        System.out.println(description + ": " + bytesToHex(data));
    }

    /**
     * Converts a byte array to a hexadecimal string.
     * @param bytes The byte array
     * @return Hexadecimal string representation of the byte array
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
    
    private byte[] readResponse() throws IOException {
        DataInputStream din = new DataInputStream(in);
        
        try {
            if (response.length < 2) return "Invalid response";
            
            // Check if this is a SearchResultEntry (0x64)
            if (response[0] == 0x64) {
                // Parse the ASN.1 structure properly
                int pos = 1;
                int length = response[pos++] & 0xFF;
                
                // Skip message ID
                pos += 2; // Assuming short form length
                
                // Skip DN
                pos += response[pos+1] + 2;
                
                // Parse attributes sequence
                if (response[pos] != 0x30) return "Invalid attribute sequence";
                pos++;
                int attrSeqLength = response[pos++] & 0xFF;
                int attrSeqEnd = pos + attrSeqLength;
                
                while (pos < attrSeqEnd) {
                    // Parse attribute sequence
                    if (response[pos] != 0x30) break;
                    pos++;
                    int attrLength = response[pos++] & 0xFF;
                    int attrEnd = pos + attrLength;
                    
                    // Get attribute type
                    if (response[pos] != 0x04) continue;
                    pos++;
                    int typeLength = response[pos++] & 0xFF;
                    String attrType = new String(response, pos, typeLength);
                    pos += typeLength;
                    
                    // Check if this is telephoneNumber
                    if (attrType.equals("telephoneNumber")) {
                        // Parse attribute values
                        if (response[pos] != 0x31) continue; // SET
                        pos++;
                        int valSetLength = response[pos++] & 0xFF;
                        int valSetEnd = pos + valSetLength;
                        
                        // Get first value
                        if (response[pos] != 0x04) continue;
                        pos++;
                        int valLength = response[pos++] & 0xFF;
                        return new String(response, pos, valLength);
                    }
                    
                    pos = attrEnd;
                }
                return "No telephone number found";
            } 
            else if (response[0] == 0x65) { // SearchResultDone
                // Parse result code properly
                int pos = 1;
                int length = response[pos++] & 0xFF;
                pos += 2; // Skip message ID
                
                if (response[pos] == 0x0A) { // ENUMERATED (result code)
                    pos++;
                    int codeLength = response[pos++] & 0xFF;
                    int resultCode = response[pos] & 0xFF;
                    
                    switch (resultCode) {
                        case 0: return "No matching friend found";
                        case 32: return "No such object";
                        case 49: return "Invalid credentials";
                        default: return "Search failed (code " + resultCode + ")";
                    }
                }
                return "Search failed";
            }
            return "Unexpected response type";
        } catch (Exception e) {
            return "Error processing response: " + e.getMessage();
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

    public String addFriend(String cn, String sn, String telephoneNumber) throws IOException {
    if (!bound) {
        return "Not authenticated";
    }

    try {
        // Clear any leftover data
        while (in.available() > 0) {
            in.skip(in.available());
        }

        // Generate unique DN (e.g., "cn=John Doe,ou=Friends,dc=example,dc=com")
        String dn = "cn=" + cn + "," + baseDN;

        // Prepare attributes
        Map<String, String[]> attributes = new LinkedHashMap<>();
        attributes.put("objectClass", new String[]{"top", "person", "inetOrgPerson"});
        attributes.put("cn", new String[]{cn});
        attributes.put("sn", new String[]{sn});
        attributes.put("telephoneNumber", new String[]{telephoneNumber});

        // Build Add Request
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        
        // LDAPMessage envelope
        request.write(SEQ);
        int lengthPos = request.size();
        request.write(0); // placeholder for length
        
        // Message ID
        request.write(INT);
        request.write(1);
        request.write(currentMsgId.getAndIncrement());
        
        // Add Request (0x68)
        request.write(LDAP_ADD_REQUEST);
        int addRequestLengthPos = request.size();
        request.write(0); // placeholder
        
        // Entry DN
        request.write(OCTET_STR);
        writeLength(request, dn.length());
        request.write(dn.getBytes("UTF-8"));
        
        // Attributes sequence
        request.write(SEQ);
        int attrSeqLengthPos = request.size();
        request.write(0); // placeholder
        
        // Add each attribute
        for (Map.Entry<String, String[]> entry : attributes.entrySet()) {
            request.write(SEQ); // Attribute sequence
            int attrLengthPos = request.size();
            request.write(0); // placeholder
            
            // Attribute type
            request.write(OCTET_STR);
            writeLength(request, entry.getKey().length());
            request.write(entry.getKey().getBytes("UTF-8"));
            
            // Attribute values (SET)
            request.write(SET);
            int valuesLengthPos = request.size();
            request.write(0); // placeholder
            
            for (String value : entry.getValue()) {
                request.write(OCTET_STR);
                writeLength(request, value.length());
                request.write(value.getBytes("UTF-8"));
            }
            
            // Update values SET length
            updateLength(request, valuesLengthPos);
            
            // Update attribute sequence length
            updateLength(request, attrLengthPos);
        }
        
        // Update attribute sequence length
        updateLength(request, attrSeqLengthPos);
        
        // Update add request length
        updateLength(request, addRequestLengthPos);
        
        // Update LDAPMessage length
        updateLength(request, lengthPos);
        
        // Send request
        byte[] packet = request.toByteArray();
        logPacket("Sending Add Request", packet);
        out.write(packet);
        
        // Read response
        byte[] response = readResponse();
        logPacket("Received Add Response", response);
        
        // Process response
        return processAddResponse(response);
    } catch (Exception e) {
        return "Error adding friend: " + e.getMessage();
    }
}

private String processAddResponse(byte[] response) {
    try {
        if (response == null || response.length < 2) {
            return "Invalid response from server";
        }

        ByteArrayInputStream bis = new ByteArrayInputStream(response);
        DataInputStream dis = new DataInputStream(bis);
        
        // Check LDAPMessage structure
        if (dis.readByte() != SEQ) {
            return "Invalid response format";
        }
        
        readBerLength(dis); // Skip length
        
        // Skip message ID
        dis.readByte(); // INTEGER tag
        int msgIdLength = dis.readByte();
        dis.skipBytes(msgIdLength);
        
        // Check Add Response (0x69)
        if (dis.readByte() != LDAP_ADD_RESPONSE) {
            return "Not an Add Response";
        }
        
        int addRespLength = readBerLength(dis);
        if (addRespLength < 3) {
            return "Invalid Add Response length";
        }
        
        // Check result code
        if (dis.readByte() != ENUM) {
            return "Missing result code";
        }
        
        int resultCodeLength = dis.readByte();
        int resultCode = dis.readByte();
        
        if (resultCode == SUCCESS) {
            return "Friend added successfully";
        } else {
            return "Server returned error: " + getResultMessage(resultCode);
        }
    } catch (Exception e) {
        return "Error processing response: " + e.getMessage();
    }
}

private void updateLength(ByteArrayOutputStream out, int lengthPos) throws IOException {
    byte[] content = out.toByteArray();
    int length = content.length - lengthPos - 1;
    
    if (length > 127) {
        byte[] lenBytes = encodeBerLength(length);
        byte[] newContent = new byte[content.length + lenBytes.length - 1];
        System.arraycopy(content, 0, newContent, 0, lengthPos);
        System.arraycopy(lenBytes, 0, newContent, lengthPos, lenBytes.length);
        System.arraycopy(content, lengthPos + 1, newContent, 
                      lengthPos + lenBytes.length, content.length - lengthPos - 1);
        out.reset();
        out.write(newContent);
    } else {
        content[lengthPos] = (byte)length;
        out.reset();
        out.write(content);
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
            System.out.println("\nMenu:");
            System.out.println("1. Search friend");
            System.out.println("2. Add friend");
            System.out.println("3. Exit");
            System.out.print("Choose option: ");
            String choice = scanner.nextLine().trim();
            
            switch (choice) {
                case "1":
                    System.out.print("\nEnter friend's name to search: ");
                    String name = scanner.nextLine().trim();
                    if (!name.isEmpty()) {
                        System.out.print("Exact match? (y/n): ");
                        boolean exact = scanner.nextLine().trim().equalsIgnoreCase("y");
                        String result = client.searchFriend(name, exact);
                        System.out.println("Result: " + result);
                    }
                    break;
                    
                case "2":
                    System.out.println("\nAdd new friend:");
                    System.out.print("First name: ");
                    String firstName = scanner.nextLine().trim();
                    System.out.print("Last name: ");
                    String lastName = scanner.nextLine().trim();
                    System.out.print("Telephone number: ");
                    String phone = scanner.nextLine().trim();
                    
                    if (!firstName.isEmpty() && !lastName.isEmpty()) {
                        String fullName = firstName + " " + lastName;
                        String result = client.addFriend(fullName, lastName, phone);
                        System.out.println("Result: " + result);
                        
                        // Verify the addition
                        System.out.println("Verifying addition...");
                        System.out.println(client.searchFriend(fullName, true));
                    } else {
                        System.out.println("First name and last name are required!");
                    }
                    break;
                    
                case "3":
                    running = false;
                    break;
                    
                default:
                    System.out.println("Invalid choice, please try again.");
            }
        }
        
        client.disconnect();
        scanner.close();
        System.out.println("Goodbye!");
    }
}