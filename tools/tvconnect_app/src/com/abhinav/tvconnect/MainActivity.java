package com.abhinav.tvconnect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#080B12"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 48, 64, 48);
        scroll.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("JioSTB TV Connect & Anywhere Mirror Gateway");
        title.setTextColor(Color.parseColor("#F1F5F9"));
        title.setTextSize(26f);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#93C5FD"));
        statusText.setTextSize(17f);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setPadding(0, 24, 0, 28);
        root.addView(statusText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);

        Button btnTailscale = makeButton("Open Tailscale (Link Anywhere Access)");
        btnTailscale.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
            if (i != null) startActivity(i);
        });
        row.addView(btnTailscale);

        Button btnRefresh = makeButton("Refresh Status");
        btnRefresh.setOnClickListener(v -> refresh());
        row.addView(btnRefresh);

        Button btnAcc = makeButton("Accessibility Settings");
        btnAcc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        row.addView(btnAcc);

        root.addView(row);
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setPadding(28, 18, 28, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 20, 0);
        b.setLayoutParams(lp);
        b.setFocusable(true);
        return b;
    }

    private void refresh() {
        String wifi = TokenStore.getIfaceIP("wlan", "eth");
        String ts = TokenStore.getIfaceIP("tun");
        String token = TokenStore.getToken(this);
        String urlLocal = (wifi.isEmpty() ? "<stb-ip>" : wifi) + ":8088";
        String urlTs = (ts.isEmpty() ? "<sign into Tailscale>" : ts + ":8088");
        StringBuilder sb = new StringBuilder();
        sb.append("Wi-Fi / LAN IP : ").append(wifi.isEmpty() ? "none" : wifi).append("\n");
        sb.append("Tailscale IP   : ").append(ts.isEmpty() ? "NOT CONNECTED YET (click Open Tailscale)" : ts).append("\n");
        sb.append("API Token      : ").append(token).append("\n\n");
        sb.append("Web Mirror UI  : http://").append(urlLocal).append("/?token=").append(token).append("\n");
        sb.append("Anywhere URL   : http://").append(urlTs).append("/?token=").append(token).append("\n\n");
        sb.append("Reboot Service : ").append(TvAccessibilityService.running ? "ACTIVE (Accessibility Gateway)" : "INACTIVE").append("\n");
        sb.append("Fallback Ports : :8088=").append(TvAccessibilityService.port8088Bound)
          .append("  :8089=").append(TvAccessibilityService.port8089Bound).append("\n\n");
        sb.append("Remote Button Remaps (active while Accessibility enabled):\n");
        sb.append("  RED    -> Cinejoy (com.abhinav.cinejoy)\n");
        sb.append("  GREEN  -> SmartTube (org.smarttube.stable)\n");
        sb.append("  YELLOW -> NetMirror (app.netmirror.newtv)\n");
        sb.append("  BLUE   -> Stremio (com.stremio.one)\n");
        sb.append("  EXPLORER/STEM1 -> TV Connect\n");
        statusText.setText(sb.toString());
    }
}

