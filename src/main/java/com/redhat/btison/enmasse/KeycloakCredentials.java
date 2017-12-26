package com.redhat.btison.enmasse;

public class KeycloakCredentials {

    private final String username;
    private final String password;

    public KeycloakCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
