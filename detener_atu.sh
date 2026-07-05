#!/bin/bash

# Script de parada del Sistema ATU
# Uso: ./detener_atu.sh

echo "========================================"
echo "  SISTEMA ATU - Deteniendo servicios... "
echo "========================================"

echo ""
echo "Deteniendo buses..."
pkill -f "BusCliente" 2>/dev/null && echo "  Buses detenidos" || echo "  (no habia buses corriendo)"

echo "Deteniendo ServidorATU..."
pkill -f "ServidorATU" 2>/dev/null && echo "  ServidorATU detenido" || echo "  (no habia servidor corriendo)"

echo "Deteniendo Dashboard web..."
pkill -f "http.server 7070" 2>/dev/null && echo "  Dashboard detenido" || echo "  (no habia dashboard corriendo)"

echo "Deteniendo HAProxy..."
sudo systemctl stop haproxy && echo "  HAProxy detenido"

echo ""
echo "========================================"
echo "  Todo detenido."
echo "========================================"
