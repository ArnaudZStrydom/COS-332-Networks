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

            // Send a simple LDAP bind request
            String bindRequest =
        }
    }
}
