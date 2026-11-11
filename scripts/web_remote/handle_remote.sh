#!/system/bin/sh
# handle_remote.sh - Token-Authenticated TV Connection API for Toybox Netcat

TOKEN_FILE="/data/local/tmp/api_token.txt"
API_TOKEN=""
[ -f "$TOKEN_FILE" ] && API_TOKEN=$(tr -d '\r\n' < "$TOKEN_FILE")

read -r req
req=$(printf "%s" "$req" | tr -d '\r')

auth_hdr=""
while IFS= read -r line; do
  line=$(printf "%s" "$line" | tr -d '\r')
  [ -z "$line" ] && break
  case "$line" in
    Authorization:*|authorization:*|X-API-Key:*|x-api-key:*)
      auth_hdr="$line"
      ;;
  esac
done

method=$(printf "%s" "$req" | awk '{print $1}')
raw_path=$(printf "%s" "$req" | awk '{print $2}')
path=${raw_path%%\?*}
query=""
case "$raw_path" in
  *\?*) query=${raw_path#*\?} ;;
esac

send_json() {
  code="$1"
  body="$2"
  len=${#body}
  printf "HTTP/1.1 %s\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, X-API-Key, Content-Type\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s" "$code" "$len" "$body"
}

if [ "$method" = "OPTIONS" ]; then
  printf "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Authorization, X-API-Key, Content-Type\r\nConnection: close\r\n\r\n"
  exit 0
fi

get_param() {
  key="$1"
  printf "%s" "$query" | tr '&' '\n' | grep "^${key}=" | head -n 1 | cut -d= -f2-
}

urldecode() {
  str=$(printf "%s" "$1" | sed 's/+/ /g')
  printf '%b' "$(printf '%s' "$str" | sed 's/%\([0-9A-Fa-f][0-9A-Fa-f]\)/\\x\1/g')"
}

# Serve UI shell at / (API endpoints still require token)
if [ "$path" = "/" ] || [ "$path" = "/index.html" ]; then
  len=$(wc -c < /data/local/tmp/remote.html)
  printf "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: %s\r\nConnection: close\r\n\r\n" "$len"
  cat /data/local/tmp/remote.html
  exit 0
fi

if [ "$path" = "/api/ping" ]; then
  send_json "200 OK" '{"ok":true,"service":"jiostb-connection-api"}'
  exit 0
fi

# Authenticate all control/device endpoints
req_tok=$(get_param "token")
if [ -n "$API_TOKEN" ]; then
  authorized=0
  [ "$req_tok" = "$API_TOKEN" ] && authorized=1
  case "$auth_hdr" in
    *"$API_TOKEN"*) authorized=1 ;;
  esac
  if [ "$authorized" -eq 0 ]; then
    send_json "401 Unauthorized" '{"ok":false,"error":"unauthorized"}'
    exit 0
  fi
fi

case "$path" in
  /api/health)
    model=$(getprop ro.product.model)
    wifi_ip=$(ip -o -4 addr show wlan0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -n 1)
    ime=$(settings get secure default_input_method 2>/dev/null)
    send_json "200 OK" "{\"ok\":true,\"model\":\"$model\",\"wifi_ip\":\"$wifi_ip\",\"ime\":\"$ime\"}"
    ;;
  /api/key|/k)
    code=$(get_param "code")
    [ -z "$code" ] && code=$(get_param "c")
    code=$(printf "%s" "$code" | tr -cd '0-9')
    if [ -n "$code" ]; then
      /system/bin/input keyevent "$code" >/dev/null 2>&1 &
      send_json "200 OK" "{\"ok\":true,\"key\":$code}"
    else
      send_json "400 Bad Request" '{"ok":false,"error":"missing code"}'
    fi
    ;;
  /api/text|/t)
    raw=$(get_param "q")
    txt=$(urldecode "$raw")
    esc=$(printf "%s" "$txt" | sed 's/ /%s/g')
    if [ -n "$esc" ]; then
      /system/bin/input text "$esc" >/dev/null 2>&1 &
      send_json "200 OK" '{"ok":true}'
    else
      send_json "400 Bad Request" '{"ok":false,"error":"missing q"}'
    fi
    ;;
  /api/tap)
    x=$(get_param "x" | tr -cd '0-9')
    y=$(get_param "y" | tr -cd '0-9')
    if [ -n "$x" ] && [ -n "$y" ]; then
      /system/bin/input tap "$x" "$y" >/dev/null 2>&1 &
      send_json "200 OK" "{\"ok\":true,\"x\":$x,\"y\":$y}"
    else
      send_json "400 Bad Request" '{"ok":false,"error":"missing x or y"}'
    fi
    ;;
  /api/launch)
    pkg=$(get_param "pkg" | tr -cd 'A-Za-z0-9._')
    if [ -n "$pkg" ]; then
      /system/bin/monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 &
      send_json "200 OK" "{\"ok\":true,\"pkg\":\"$pkg\"}"
    else
      send_json "400 Bad Request" '{"ok":false,"error":"missing pkg"}'
    fi
    ;;
  /api/open)
    url=$(urldecode "$(get_param "url")")
    if [ -n "$url" ]; then
      /system/bin/am start -a android.intent.action.VIEW -d "$url" >/dev/null 2>&1 &
      send_json "200 OK" '{"ok":true}'
    else
      send_json "400 Bad Request" '{"ok":false,"error":"missing url"}'
    fi
    ;;
  /api/apps)
    pkgs=$(pm list packages -3 | cut -d: -f2 | sed 's/.*/"&"/' | tr '\n' ',' | sed 's/,$//')
    send_json "200 OK" "{\"ok\":true,\"packages\":[$pkgs]}"
    ;;
  /api/screen)
    /system/bin/screencap -p /data/local/tmp/_cap.png >/dev/null 2>&1
    if [ -s /data/local/tmp/_cap.png ]; then
      len=$(wc -c < /data/local/tmp/_cap.png)
      printf "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: %s\r\nConnection: close\r\n\r\n" "$len"
      cat /data/local/tmp/_cap.png
    else
      send_json "500 Internal Server Error" '{"ok":false,"error":"screencap failed"}'
    fi
    ;;
  *)
    send_json "404 Not Found" '{"ok":false,"error":"not found"}'
    ;;
esac
