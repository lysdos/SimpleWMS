package com.wms.pda.model;

public class LoginRequest {
    public String username;
    public String password;
    public LoginRequest() {}
    public LoginRequest(String u, String p) { this.username = u; this.password = p; }
}
