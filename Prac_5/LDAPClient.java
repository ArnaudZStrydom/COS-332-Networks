package Prac_5;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;

public class LDAPClient {
    private static final String LDAP_HOST = "localhost";
    private static final int LDAP_PORT = 389;


    public static void main(String[] args)
    {
        try{
            Socket socket = new Socket(LDAP_HOST, LDAP_PORT);
            System.out.println("Connected to LDAP server at " + LDAP_HOST + ":" + LDAP_PORT);
            OutputStream outputStream = socket.getOutputStream();
            InputStream inputStream = socket.getInputStream();


            // Send a simple LDAP bind request
            byte[] bindRequest = constructBindRequest("cn=admin,dc=example,dc=com", "password");
            outputStream.write(bindRequest);
            outputStream.flush();
            System.out.println("Sent bind request to LDAP server.");

            // Read the response from the server
            byte[] response = new byte[1024];
            int bytesRead = inputStream.read(response);
            System.out.println("Bind Response: " + Arrays.toString(Arrays.copyOf(response, bytesRead)));

            //Construct Search Request
            byte[] searchRequest = constructSearchRequest("dc=example,dc=com", "(objectClass=*)");
            outputStream.write(searchRequest);
            outputStream.flush();
            System.out.println("Sent search request to LDAP server.");

            // Read the response from the server
            bytesRead = inputStream.read(response);
            System.out.println("Search Response: " + Arrays.toString(Arrays.copyOf(response, bytesRead)));

            // Close the streams and socket
            socket.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static byte[] constructBindRequest(String din, String password)
    {
        byte[] dnBytes = dn.getBytes();
        byte[] passwordBytes = password.getBytes();

        return new byte[]{
            0x30, (byte) (dnBytes.length + passwordBytes.length + 11), // LDAPMessage (SEQ)
            0x02, 0x01, 0x01, // Message ID: 1 (INTEGER)
            0x60, (byte) (dnBytes.length + passwordBytes.length + 7), // BindRequest (Application[0])
            0x02, 0x01, 0x03, // Version (INTEGER: 3)
            0x04, (byte) dnBytes.length // DN (OCTET STRING)
        };
    }

    // Manually constructs an LDAP Search Request
    private static byte[] constructSearchRequest(String baseDN) {
        byte[] baseDNBytes = baseDN.getBytes();
        return new byte[]{
            0x30, (byte) (baseDNBytes.length + 12),
            0x02, 0x01, 0x02, // Message ID: 2
            0x63, (byte) (baseDNBytes.length + 8), // SearchRequest (Application[3])
            0x04, (byte) baseDNBytes.length // Base DN
        };

    }        
