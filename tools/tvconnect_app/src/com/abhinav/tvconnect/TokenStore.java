package com.abhinav.tvconnect;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class TokenStore {
    private static final String PREFS = "tvconnect_prefs";
    private static final String KEY_TOKEN = "api_token";
    private static final String[] TOKEN_PATHS = new String[] {
        "/sdcard/tv_connect_token.txt",
        "/storage/emulated/0/tv_connect_token.txt",
        "/data/local/tmp/api_token.txt"
    };

    private TokenStore() {}

    public static String getToken(Context ctx) {
        for (String path : TOKEN_PATHS) {
            try {
                File f = new File(path);
                if (f.exists() && f.canRead()) {
                    try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                        String line = br.readLine();
                        if (line != null && !line.trim().isEmpty()) {
                            String tok = line.trim();
                            saveToken(ctx, tok);
                            return tok;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getString(KEY_TOKEN, "43bqeSYTlOPeDo3gr70PmChs");
    }

    public static void saveToken(Context ctx, String token) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply();
    }

    public static String getIfaceIP(String... prefixes) {
        try {
            for (String pref : prefixes) {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    if (!ni.getName().startsWith(pref)) continue;
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }
}

