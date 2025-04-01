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
}
