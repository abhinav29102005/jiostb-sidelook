#!/system/bin/sh
# handle_remote.sh
# HTTP Request Dispatcher for Toybox Netcat HTTP Server on Android
read req
case "$req" in
  *"GET /k?c="*)
    code=$(echo "$req" | sed 's/.*c=\([0-9]*\).*/\1/')
    /system/bin/input keyevent "$code" &
    printf "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
    ;;
  *"GET /t?q="*)
    txt=$(echo "$req" | sed 's/.*q=\([^ ]*\) HTTP.*/\1/')
    /system/bin/input text "$txt" &
    printf "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
    ;;
  *)
    len=$(wc -c < /data/local/tmp/remote.html)
    printf "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: %s\r\nConnection: close\r\n\r\n" "$len"
    cat /data/local/tmp/remote.html
    ;;
esac
