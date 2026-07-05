#!/bin/bash

# Script de inicio del Sistema ATU - Monitoreo de Buses en Tiempo Real
# Uso: ./iniciar_atu.sh

DIR="/home/debian1/atu_proyecto"
LOGS="$DIR/logs"
mkdir -p "$LOGS"
IP=$(hostname -I | awk '{print $1}')

echo "========================================"
echo "  SISTEMA ATU - Iniciando servicios...  "
echo "========================================"

# PASO 1 - MariaDB
echo ""
echo "[1/6] Iniciando MariaDB..."
sudo systemctl start mariadb
if systemctl is-active --quiet mariadb; then
    echo "      MariaDB OK"
else
    echo "      ERROR: MariaDB no pudo iniciar. Abortando."
    exit 1
fi

# PASO 2 - HAProxy
echo ""
echo "[2/6] Iniciando HAProxy..."
sudo systemctl start haproxy
if systemctl is-active --quiet haproxy; then
    echo "      HAProxy OK"
else
    echo "      ERROR: HAProxy no pudo iniciar. Abortando."
    exit 1
fi

# PASO 3 - Servidor principal
echo ""
echo "[3/6] Iniciando ServidorATU principal (TCP:5050 HTTP:8080)..."
cd "$DIR"
nohup java -cp .:mysql-connector-j.jar ServidorATU 5050 8080 > "$LOGS/servidor_principal.log" 2>&1 &
PID_PRINCIPAL=$!
sleep 2
if kill -0 $PID_PRINCIPAL 2>/dev/null; then
    echo "      ServidorATU principal OK (PID $PID_PRINCIPAL)"
else
    echo "      ERROR: ServidorATU principal no pudo iniciar."
    exit 1
fi

# PASO 4 - Servidor respaldo
echo ""
echo "[4/6] Iniciando ServidorATU respaldo (TCP:5051 HTTP:8081)..."
nohup java -cp .:mysql-connector-j.jar ServidorATU 5051 8081 > "$LOGS/servidor_respaldo.log" 2>&1 &
PID_RESPALDO=$!
sleep 2
if kill -0 $PID_RESPALDO 2>/dev/null; then
    echo "      ServidorATU respaldo OK (PID $PID_RESPALDO)"
else
    echo "      ERROR: ServidorATU respaldo no pudo iniciar."
    exit 1
fi

# PASO 5 - Dashboard web
echo ""
echo "[5/6] Iniciando Dashboard web (puerto 7070)..."
nohup python3 -m http.server 7070 --bind 0.0.0.0 > "$LOGS/dashboard.log" 2>&1 &
PID_DASHBOARD=$!
sleep 1
if kill -0 $PID_DASHBOARD 2>/dev/null; then
    echo "      Dashboard OK (PID $PID_DASHBOARD)"
else
    echo "      ERROR: Dashboard no pudo iniciar."
    exit 1
fi

# PASO 6 - Buses clientes
echo ""
echo "[6/6] Iniciando buses (BUS-101 al BUS-606)..."
nohup java BusCliente BUS-101 localhost 5050 > "$LOGS/bus101.log" 2>&1 &
nohup java BusCliente BUS-202 localhost 5050 > "$LOGS/bus202.log" 2>&1 &
nohup java BusCliente BUS-303 localhost 5050 > "$LOGS/bus303.log" 2>&1 &
nohup java BusCliente BUS-404 localhost 5050 > "$LOGS/bus404.log" 2>&1 &
nohup java BusCliente BUS-505 localhost 5050 > "$LOGS/bus505.log" 2>&1 &
nohup java BusCliente BUS-606 localhost 5050 > "$LOGS/bus606.log" 2>&1 &
sleep 2
echo "      Buses iniciados OK"

# RESUMEN
echo ""
echo "========================================"
echo "  SISTEMA ATU - Todo listo!             "
echo "========================================"
echo ""
echo "  Dashboard:  http://$IP:7070/dashboard.html"
echo "  API buses:  http://$IP:8080/buses"
echo "  HAProxy:    http://$IP:9999/stats"
echo ""
echo "  Logs disponibles en:"
echo "    $LOGS/servidor_principal.log"
echo "    $LOGS/servidor_respaldo.log"
echo "    $LOGS/bus101.log  $LOGS/bus202.log  $LOGS/bus303.log"
echo ""
echo "  Para detener todo: ./detener_atu.sh"
echo "========================================"
