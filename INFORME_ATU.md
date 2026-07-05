# INFORME TÉCNICO
## Sistema de Monitoreo de Buses en Tiempo Real
### Autoridad de Transporte Urbano de Lima y Callao (ATU)

---

**Institución:** Universidad / Curso de Sistemas Distribuidos  
**Fecha:** 22 de junio de 2026  
**Entorno:** Debian GNU/Linux 13 (Trixie) — x86_64  
**IP del servidor:** 10.230.60.102  
**Alumno:** debian1  

---

## 1. OBJETIVO

Implementar un sistema distribuido de monitoreo de buses en tiempo real para la ATU,
compuesto por: servidores TCP/HTTP en Java, base de datos MariaDB, balanceador de
carga HAProxy, firewall con nftables, y un dashboard web con mapa interactivo Leaflet.js.

---

## 2. ARQUITECTURA DEL SISTEMA

```
  [BusCliente x3]
   BUS-101, BUS-202, BUS-303
        |  TCP :5050
        v
  ┌─────────────────────────┐
  │   ServidorATU (Java)    │  ← Principal  :5050 / HTTP :8080
  │   ServidorATU (Java)    │  ← Respaldo   :5051 / HTTP :8081
  └──────────┬──────────────┘
             │
             ▼
        [MariaDB :3306]
        atu_monitoreo.ubicaciones

  [HAProxy]
    TCP  balanceador → :9000  (principal:5050 | respaldo:5051)
    HTTP balanceador → :9001  (principal:8080 | respaldo:8081)
    Stats panel      → :9999/stats

  [nftables]  policy DROP + whitelist puertos permitidos

  [Dashboard]
    dashboard.html → Leaflet.js → fetch /buses cada 3s
    Servido en http://10.230.60.102:7070/dashboard.html
```

---

## 3. PASO 1 — INSTALACIÓN DE PAQUETES

### Comando ejecutado:
```bash
sudo apt-get install -y openjdk-21-jdk mariadb-server haproxy nftables git net-tools
```

### Resultado — Versiones instaladas:

| Paquete      | Versión                          |
|--------------|----------------------------------|
| OpenJDK      | 21.0.11 (build 10-1-deb13u2)    |
| MariaDB      | 11.8.6-MariaDB                   |
| HAProxy      | 3.0.11-1+deb13u3                 |
| nftables     | v1.1.3 (Commodore Bullmoose #4)  |
| git          | 2.47.3                           |
| net-tools    | 2.10                             |

---

## 4. PASO 2 — BASE DE DATOS MariaDB

### Comandos ejecutados:
```sql
CREATE DATABASE atu_monitoreo CHARACTER SET utf8mb4;
CREATE TABLE ubicaciones (
    id        INT AUTO_INCREMENT PRIMARY KEY,
    id_bus    VARCHAR(20) NOT NULL,
    latitud   DECIMAL(10,8) NOT NULL,
    longitud  DECIMAL(11,8) NOT NULL,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE USER 'atu_app'@'localhost' IDENTIFIED BY '***';
GRANT ALL PRIVILEGES ON atu_monitoreo.* TO 'atu_app'@'localhost';
```

### Resultado — Estructura de la tabla `ubicaciones`:

| Campo     | Tipo           | Clave | Extra          |
|-----------|----------------|-------|----------------|
| id        | int(11)        | PRI   | auto_increment |
| id_bus    | varchar(20)    | MUL   |                |
| latitud   | decimal(10,8)  |       |                |
| longitud  | decimal(11,8)  |       |                |
| timestamp | datetime       | MUL   | current_ts()   |

### Resultado — Usuario creado:
```
User: atu_app  |  Host: localhost
GRANT ALL PRIVILEGES ON atu_monitoreo.* TO 'atu_app'@'localhost'
```

### Resultado — Registros almacenados (muestra últimos 9 de 117+):
```
id  | id_bus  | latitud      | longitud     | timestamp
117 | BUS-101 | -12.02446000 | -77.05098400 | 2026-06-22 03:30:58
116 | BUS-202 | -12.06549800 | -77.05578000 | 2026-06-22 03:30:58
115 | BUS-303 | -12.03074100 | -77.04920000 | 2026-06-22 03:30:57
114 | BUS-101 | -12.02351100 | -77.05074200 | 2026-06-22 03:30:55
113 | BUS-202 | -12.06507800 | -77.05671900 | 2026-06-22 03:30:54
112 | BUS-303 | -12.03023200 | -77.04926400 | 2026-06-22 03:30:54
...
Total de registros: 117+
```

---

## 5. PASO 3 — CÓDIGO JAVA

### Archivos creados y compilados:

| Archivo            | Tamaño | Clase compilada     | Java SE |
|--------------------|--------|---------------------|---------|
| ServidorATU.java   | 6.1K   | ServidorATU.class   | 21      |
| BusCliente.java    | 2.0K   | BusCliente.class    | 21      |
| mysql-connector-j  | 2.4MB  | JDBC Driver v8.3.0  | —       |

### Descripción ServidorATU.java:
- Servidor TCP multihilo (puerto 5050) que acepta mensajes formato `ID_BUS|lat|lon`
- Almacena cada ubicación en MariaDB via JDBC
- Servidor HTTP (puerto 8080) con endpoint `/buses` que devuelve JSON
- Endpoint `/health` para health checks de HAProxy
- Acepta puertos como argumentos (`java ServidorATU 5051 8081` para instancia respaldo)

### Descripción BusCliente.java:
- Conecta al servidor TCP y envía ubicaciones simuladas cada 3 segundos
- Simula movimiento gradual en coordenadas de Lima/Callao
- Recibe confirmación `OK|ID_BUS|timestamp` del servidor

### Comando de compilación:
```bash
javac -cp .:mysql-connector-j.jar ServidorATU.java   # OK
javac BusCliente.java                                  # OK
```

---

## 6. PASO 4 — SEGURIDAD: nftables

### Configuración aplicada (`/etc/nftables.conf`):
```
table inet filter {
    chain input {
        type filter hook input priority filter; policy DROP;
        iif "lo" accept
        ct state established,related accept
        ip protocol icmp accept
        tcp dport 22 accept      # SSH administración
        tcp dport 5050 accept    # Servidor TCP principal
        tcp dport 8080 accept    # HTTP API principal
        tcp dport 9000 accept    # HAProxy TCP
        tcp dport 5051 accept    # Servidor TCP respaldo
        tcp dport 8081 accept    # HTTP API respaldo
        tcp dport 7070 accept    # Dashboard web
        log prefix "[NFT DROP] " drop
    }
    chain forward { policy drop; }
    chain output  { policy accept; }
}
```

**Política por defecto:** DROP (deniega todo tráfico no listado explícitamente)

---

## 7. PASO 4b — DISPONIBILIDAD: HAProxy

### Configuración (`/etc/haproxy/haproxy.cfg`):

**Frontend TCP :9000** → Backend round-robin:
- Servidor principal: `localhost:5050` — health check cada 2s
- Servidor respaldo:  `localhost:5051` — backup (solo si principal cae)

**Frontend HTTP :9001** → Backend round-robin:
- Servidor principal: `localhost:8080` — health check `GET /health`
- Servidor respaldo:  `localhost:8081` — backup

**Panel estadísticas:** `http://10.230.60.102:9999/stats`

### Prueba de failover:
1. Servidor principal (PID 16579) **terminado con `kill`**
2. HAProxy detectó la caída via health check (intervalo: 2s, umbral: 3 fallos)
3. Tras ~6 segundos, el tráfico fue redirigido automáticamente al respaldo (:8081)
4. El endpoint `/buses` continuó respondiendo sin interrupción desde el respaldo
5. Servidor principal restaurado → HAProxy reincorporó el nodo automáticamente

---

## 8. PASO 5 — DASHBOARD WEB

**URL:** `http://10.230.60.102:7070/dashboard.html`  
**Tecnologías:** HTML5, Leaflet.js v1.9.4, JavaScript ES6, OpenStreetMap

### Funcionalidades:
- Mapa interactivo centrado en Lima/Callao (lat: -12.0464, lon: -77.0428)
- Marcadores de buses con íconos diferenciados por color según ID
- Trayectorias históricas (últimos 20 puntos) por bus
- Actualización automática cada 3 segundos via `fetch('/buses')`
- Panel lateral con lista de buses activos, coordenadas y timestamp
- Barra de estado con indicador de conexión en tiempo real
- Panel de referencia: Plaza Mayor de Lima

---

## 9. PASO 6 — PRUEBAS Y EVIDENCIA

### 9.1 Puertos activos (`ss -tulnp`):
```
Proto  Puerto   Proceso          Descripción
tcp    3306     mariadbd         Base de datos MariaDB
tcp    5050     java (PID 20893) ServidorATU TCP principal
tcp    5051     java (PID 17831) ServidorATU TCP respaldo
tcp    8080     java (PID 20893) ServidorATU HTTP principal
tcp    8081     java (PID 17831) ServidorATU HTTP respaldo
tcp    9000     haproxy          Balanceador TCP
tcp    9001     haproxy          Balanceador HTTP
tcp    9999     haproxy          Panel de estadísticas
tcp    7070     python3          Servidor dashboard web
```

### 9.2 Logs del servidor recibiendo conexiones:
```
[ATU] Servidor iniciando...
[ATU] TCP  puerto: 5050
[ATU] HTTP puerto: 8080
[TCP] Escuchando en puerto 5050
[HTTP] Servidor HTTP activo en puerto 8080
[HTTP] GET /buses -> 295 bytes   (x25 peticiones del dashboard)
[TCP] Recibido de 127.0.0.1: BUS-101|-12.035547|-77.036307
[DB]  Guardado: Bus=BUS-101 lat=-12.035547 lon=-77.036307
```

### 9.3 Logs de los 3 clientes bus simultáneos:
```
[BUS-101] Enviado: BUS-101|-12.035547|-77.036307
[BUS-101] Respuesta: OK|BUS-101|1782113970666

[BUS-202] Enviado: BUS-202|-12.056138|-77.035226
[BUS-202] Respuesta: OK|BUS-202|1782113982968

[BUS-303] Enviado: BUS-303|-12.027292|-77.043013
[BUS-303] Respuesta: OK|BUS-303|1782113982968
```

### 9.4 Respuesta JSON del endpoint `/buses`:
```json
[
    {
        "id_bus": "BUS-202",
        "latitud": -12.063286,
        "longitud": -77.052309,
        "timestamp": "2026-06-22 03:30:58"
    },
    {
        "id_bus": "BUS-101",
        "latitud": -12.022660,
        "longitud": -77.047640,
        "timestamp": "2026-06-22 03:30:58"
    },
    {
        "id_bus": "BUS-303",
        "latitud": -12.030169,
        "longitud": -77.048884,
        "timestamp": "2026-06-22 03:30:57"
    }
]
```

### 9.5 Prueba de failover HAProxy:
```
[ANTES]  curl http://localhost:9001/buses  →  HTTP 200 (desde :8080)
[FALLA]  kill <PID servidor principal>
[ESPERA] HAProxy health check detecta caída (~6s)
[DESPUÉS] curl http://localhost:9001/buses  →  HTTP 200 (desde :8081 RESPALDO)
[RESULTADO] Servicio continuó sin interrupción ✓
```

---

## 10. CONCLUSIONES

1. **Comunicación TCP/IP:** El servidor acepta múltiples clientes concurrentes usando
   un `ExecutorService` con pool de hilos, procesando el protocolo `ID|lat|lon`.

2. **Persistencia:** MariaDB almacena cada ubicación con timestamp automático.
   La consulta `/buses` devuelve la última posición por bus mediante `GROUP BY id_bus`.

3. **API REST:** El servidor HTTP interno de Java (`com.sun.net.httpserver`)
   expone el endpoint `/buses` con JSON y cabeceras CORS.

4. **Seguridad:** nftables implementa política DROP por defecto, permitiendo
   únicamente los puertos necesarios para el funcionamiento del sistema.

5. **Alta disponibilidad:** HAProxy detecta fallos del servidor principal mediante
   health checks cada 2 segundos y redirige automáticamente al servidor de respaldo,
   logrando failover transparente en menos de 10 segundos.

6. **Visualización:** El dashboard Leaflet.js consume el endpoint REST cada 3 segundos,
   mostrando los buses en tiempo real sobre el mapa de Lima/Callao con trayectorias.

---

## 11. SERVICIOS AL INICIO DEL SISTEMA

| Servicio  | Comando                        | Estado    |
|-----------|--------------------------------|-----------|
| MariaDB   | `systemctl enable mariadb`     | Habilitado|
| HAProxy   | `systemctl enable haproxy`     | Habilitado|
| nftables  | `systemctl enable nftables`    | Habilitado|

---

*Informe generado el 22/06/2026 — Sistema ATU Monitor v1.0*
