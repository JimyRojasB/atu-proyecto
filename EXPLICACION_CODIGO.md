# EXPLICACIÓN DETALLADA DEL CÓDIGO
## Proyecto ATU — Sistema de Monitoreo de Buses en Tiempo Real

**Curso:** Sistemas Distribuidos  
**Fecha:** 22 de junio de 2026 (actualizado 05 de julio de 2026 — se agregaron las Partes 8 y 9)  
**Alumno:** debian1  

---

# PARTE 1 — ¿QUÉ ES EL SISTEMA EN GENERAL?

Imagina que la ATU (Autoridad de Transporte Urbano de Lima) quiere saber **dónde están sus buses en todo momento**.

Este sistema hace exactamente eso:

- Cada bus tiene un programa que **envía su ubicación GPS** cada 3 segundos
- Un servidor central **recibe y guarda** esas ubicaciones
- Una página web muestra un **mapa en tiempo real** con los buses moviéndose

```
┌──────────┐        ┌────────────────┐        ┌──────────────┐
│  BUS-101 │──GPS──►│  ServidorATU   │──DB───►│   MariaDB    │
│  BUS-202 │──GPS──►│  (Java)        │        │  (ubicac.)   │
│  BUS-303 │──GPS──►│                │        └──────────────┘
└──────────┘        └────────┬───────┘
                             │ JSON
                             ▼
                    ┌────────────────┐
                    │  Dashboard Web │
                    │  (Mapa Lima)   │
                    └────────────────┘
```

---

# PARTE 2 — ARCHIVO: BusCliente.java

## ¿Qué hace este archivo?

Simula un bus real enviando su posición GPS al servidor. En la vida real, este programa estaría instalado en el GPS del bus.

## Explicación línea por línea

```java
import java.io.*;
import java.net.*;
import java.util.Random;
```
**¿Qué son los imports?**
Son "bibliotecas" que Java necesita para hacer ciertas cosas:
- `java.io` → para leer y escribir texto
- `java.net` → para conectarse por red (internet/red local)
- `java.util.Random` → para generar números aleatorios (simular movimiento)

---

```java
static final double LAT_BASE = -12.0464;
static final double LON_BASE = -77.0428;
static final double RANGO    =  0.05;
```
**¿Qué son estas variables?**
- `LAT_BASE` y `LON_BASE` son las coordenadas del **centro de Lima** (Plaza Mayor)
- `RANGO = 0.05` significa que los buses se moverán en un radio de aproximadamente **5 km** alrededor del centro
- Son `final` porque no cambian: son como puntos de referencia fijos

---

```java
String idBus = (args.length > 0) ? args[0] : "BUS-001";
String host  = (args.length > 1) ? args[1] : "localhost";
int    port  = (args.length > 2) ? Integer.parseInt(args[2]) : 5050;
```
**¿Qué hace esto?**
Cuando ejecutas el programa, puedes pasarle parámetros:

```bash
java BusCliente BUS-101 localhost 5050
```

- `BUS-101` → nombre del bus
- `localhost` → dirección del servidor
- `5050` → puerto del servidor

Si no pones nada, usa los valores por defecto (BUS-001, localhost, 5050).

---

```java
double lat = LAT_BASE + (rnd.nextDouble() - 0.5) * RANGO;
double lon = LON_BASE + (rnd.nextDouble() - 0.5) * RANGO;
```
**¿Qué hace esto?**
Coloca al bus en una **posición aleatoria inicial** cerca del centro de Lima.

Por ejemplo, si `rnd.nextDouble()` da 0.7:
- `(0.7 - 0.5) * 0.05 = 0.01`
- El bus empieza en `-12.0464 + 0.01 = -12.0364` (un poco al norte del centro)

---

```java
try (Socket socket = new Socket(host, port);
     PrintWriter pw = new PrintWriter(socket.getOutputStream(), true);
     BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
```
**¿Qué hace esto?**
Abre una **conexión TCP** con el servidor. Es como marcar un número de teléfono:
- `Socket` = la "llamada telefónica" entre el bus y el servidor
- `PrintWriter` = para **enviar** mensajes al servidor
- `BufferedReader` = para **recibir** respuestas del servidor

El `try (...)` con paréntesis es un "try-with-resources": si algo falla, Java cierra la conexión automáticamente.

---

```java
while (true) {
    lat += (rnd.nextDouble() - 0.5) * 0.002;
    lon += (rnd.nextDouble() - 0.5) * 0.002;
```
**¿Qué hace esto?**
Bucle infinito que simula el movimiento del bus. Cada vez que se ejecuta:
- Cambia la latitud un poquito (máximo ±0.001 grados ≈ ±111 metros)
- Cambia la longitud un poquito también
- Esto simula que el bus se mueve gradualmente por las calles

---

```java
String mensaje = idBus + "|" + String.format("%.6f", lat) + "|" + String.format("%.6f", lon);
pw.println(mensaje);
```
**¿Qué hace esto?**
Construye y envía el mensaje al servidor con este formato:

```
BUS-101|-12.035547|-77.036307
```

- `|` es el separador entre campos (como una coma en un CSV)
- `%.6f` formatea el número con 6 decimales (precisión de ~11 cm)

---

```java
String respuesta = br.readLine();
System.out.println("[" + idBus + "] Respuesta: " + respuesta);
Thread.sleep(3000);
```
**¿Qué hace esto?**
- Espera la **confirmación del servidor** (ejemplo: `OK|BUS-101|1782113970666`)
- La muestra en pantalla
- **Pausa 3 segundos** antes de enviar la siguiente ubicación

---

# PARTE 3 — ARCHIVO: ServidorATU.java

## ¿Qué hace este archivo?

Es el **corazón del sistema**. Hace dos cosas al mismo tiempo:
1. Escucha las ubicaciones que envían los buses (servidor TCP)
2. Responde al dashboard web con las posiciones actuales (servidor HTTP)

## Explicación parte por parte

### 3.1 — Variables de configuración

```java
static final String DB_URL  = "jdbc:mysql://localhost:3306/atu_monitoreo";
static final String DB_USER = "atu_app";
static final String DB_PASS = "atu_secure_2024";

static int TCP_PORT  = 5050;
static int HTTP_PORT = 8080;
```

**¿Qué son?**
- `DB_URL` → dirección de la base de datos. Se lee así: "conéctate a MySQL en esta máquina, puerto 3306, base de datos llamada atu_monitoreo"
- `DB_USER` y `DB_PASS` → usuario y contraseña para entrar a la base de datos
- `TCP_PORT = 5050` → por este puerto escucha a los buses
- `HTTP_PORT = 8080` → por este puerto responde al dashboard web

---

### 3.2 — Método main (punto de entrada)

```java
public static void main(String[] args) throws Exception {
    if (args.length > 0) TCP_PORT  = Integer.parseInt(args[0]);
    if (args.length > 1) HTTP_PORT = Integer.parseInt(args[1]);
```

**¿Por qué esto es importante?**
Permite ejecutar **dos instancias del servidor** con puertos distintos:

```bash
java -cp .:mysql-connector-j.jar ServidorATU        # Principal: 5050 y 8080
java -cp .:mysql-connector-j.jar ServidorATU 5051 8081  # Respaldo: 5051 y 8081
```

Así si el servidor principal cae, el respaldo ya está listo.

---

```java
new Thread(() -> iniciarTCP()).start();
iniciarHTTP();
```

**¿Qué hace esto?**
Arranca DOS servicios al mismo tiempo:
- `new Thread(...)` → abre el servidor TCP en un **hilo separado** (en paralelo)
- `iniciarHTTP()` → abre el servidor HTTP en el hilo principal

Sin el `Thread`, uno bloquearía al otro y el servidor no funcionaría.

---

### 3.3 — Método iniciarTCP()

```java
static void iniciarTCP() {
    try (ServerSocket ss = new ServerSocket(TCP_PORT)) {
        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            Socket cliente = ss.accept();
            pool.submit(() -> manejarCliente(cliente));
        }
    }
}
```

**¿Qué hace esto paso a paso?**

1. `ServerSocket ss = new ServerSocket(TCP_PORT)` → Abre el puerto 5050 y se pone a **escuchar**. Es como poner el teléfono en modo espera.

2. `ss.accept()` → **Espera** hasta que un bus se conecte. Cuando llega, devuelve el socket de ese bus.

3. `pool.submit(() -> manejarCliente(cliente))` → Cada bus que se conecta se atiende en un **hilo separado**. Así pueden conectarse los 3 buses al mismo tiempo sin que uno tenga que esperar a que el otro termine.

4. `ExecutorService pool = Executors.newCachedThreadPool()` → Es un "pool de hilos". Crea hilos nuevos cuando se necesitan y los reutiliza cuando están libres. Eficiente y escalable.

---

### 3.4 — Método manejarCliente()

```java
static void manejarCliente(Socket socket) {
    String ip = socket.getInetAddress().getHostAddress();
    try (BufferedReader br = ...; PrintWriter pw = ...) {
        String linea;
        while ((linea = br.readLine()) != null) {
            String[] partes = linea.split("\\|");
            if (partes.length == 3) {
                String idBus    = partes[0].trim();
                double latitud  = Double.parseDouble(partes[1].trim());
                double longitud = Double.parseDouble(partes[2].trim());
                guardarUbicacion(idBus, latitud, longitud);
                pw.println("OK|" + idBus + "|" + System.currentTimeMillis());
            } else {
                pw.println("ERROR|SE_ESPERAN_3_CAMPOS");
            }
        }
    }
}
```

**¿Qué hace esto paso a paso?**

1. `br.readLine()` → Lee una línea del bus. Ejemplo recibido: `BUS-101|-12.035547|-77.036307`

2. `linea.split("\\|")` → Divide el mensaje por el separador `|`. Resultado:
   - `partes[0]` = `"BUS-101"`
   - `partes[1]` = `"-12.035547"`
   - `partes[2]` = `"-77.036307"`

3. `if (partes.length == 3)` → Valida que el mensaje tenga exactamente 3 partes. Si no, responde con ERROR.

4. `guardarUbicacion(...)` → Guarda en la base de datos.

5. `pw.println("OK|BUS-101|timestamp")` → Confirma al bus que todo salió bien.

---

### 3.5 — Método guardarUbicacion()

```java
static void guardarUbicacion(String idBus, double lat, double lon) {
    String sqlInsert = "INSERT INTO ubicaciones (id_bus, latitud, longitud) VALUES (?, ?, ?)";
    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
         PreparedStatement ps = conn.prepareStatement(sqlInsert)) {
        ps.setString(1, idBus);
        ps.setDouble(2, lat);
        ps.setDouble(3, lon);
        ps.executeUpdate();
    }
}
```

**¿Qué hace esto?**
Guarda la ubicación en la base de datos MariaDB.

1. `DriverManager.getConnection(...)` → Abre una conexión a la base de datos (como "iniciar sesión")

2. `PreparedStatement` → Prepara la consulta SQL con `?` como marcadores de posición. **¿Por qué no poner los valores directo?** Por seguridad: evita ataques de inyección SQL.

3. `ps.setString(1, idBus)` → Reemplaza el primer `?` con el ID del bus
4. `ps.setDouble(2, lat)` → Reemplaza el segundo `?` con la latitud
5. `ps.setDouble(3, lon)` → Reemplaza el tercer `?` con la longitud

6. `ps.executeUpdate()` → **Ejecuta** el INSERT en la base de datos

El resultado en la base de datos es una fila nueva:
```
id=118 | BUS-101 | -12.035547 | -77.036307 | 2026-06-22 03:31:01
```

---

### 3.6 — Método iniciarHTTP()

```java
static void iniciarHTTP() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);

    server.createContext("/buses", exchange -> {
        String json = obtenerUltimasUbicaciones();
        byte[] respuesta = json.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, respuesta.length);
        exchange.getResponseBody().write(respuesta);
        exchange.getResponseBody().close();
    });

    server.createContext("/health", exchange -> {
        byte[] ok = "OK".getBytes();
        exchange.sendResponseHeaders(200, ok.length);
        exchange.getResponseBody().write(ok);
        exchange.getResponseBody().close();
    });

    server.setExecutor(Executors.newFixedThreadPool(4));
    server.start();
}
```

**¿Qué hace esto?**
Crea un servidor web básico (como un mini-Apache) con dos rutas:

**Ruta `/buses`:**
- Cuando el dashboard pide `http://servidor:8080/buses`
- El servidor consulta la base de datos y devuelve un JSON con las posiciones actuales
- `Access-Control-Allow-Origin: *` → permite que la página web acceda desde cualquier origen (CORS)

**Ruta `/health`:**
- Solo responde `"OK"` con código 200
- La usa HAProxy para verificar que el servidor está vivo cada 2 segundos
- Si no responde, HAProxy asume que cayó y redirige al respaldo

---

### 3.7 — Método obtenerUltimasUbicaciones()

```java
static String obtenerUltimasUbicaciones() {
    String sql = "SELECT id_bus, latitud, longitud, MAX(timestamp) as ultima " +
                 "FROM ubicaciones GROUP BY id_bus ORDER BY ultima DESC";
    StringBuilder sb = new StringBuilder("[");
    // ... recorre los resultados y construye el JSON
    sb.append("]");
    return sb.toString();
}
```

**¿Qué hace la consulta SQL?**

```sql
SELECT id_bus, latitud, longitud, MAX(timestamp) as ultima
FROM ubicaciones
GROUP BY id_bus
ORDER BY ultima DESC
```

- `GROUP BY id_bus` → agrupa todos los registros por bus. De cientos de registros, saca **uno por bus**
- `MAX(timestamp)` → toma el registro más **reciente** de cada bus
- `ORDER BY ultima DESC` → ordena del más reciente al más antiguo

**Resultado que devuelve (JSON):**
```json
[
    {"id_bus":"BUS-101","latitud":-12.022660,"longitud":-77.047640,"timestamp":"2026-06-22 03:30:58"},
    {"id_bus":"BUS-202","latitud":-12.063286,"longitud":-77.052309,"timestamp":"2026-06-22 03:30:58"},
    {"id_bus":"BUS-303","latitud":-12.030169,"longitud":-77.048884,"timestamp":"2026-06-22 03:30:57"}
]
```

---

# PARTE 4 — BASE DE DATOS MariaDB

## ¿Qué guarda?

La tabla `ubicaciones` guarda **cada vez** que un bus envía su posición:

| id | id_bus  | latitud      | longitud     | timestamp           |
|----|---------|--------------|--------------|---------------------|
| 1  | BUS-101 | -12.035547   | -77.036307   | 2026-06-22 03:30:01 |
| 2  | BUS-202 | -12.056138   | -77.035226   | 2026-06-22 03:30:01 |
| 3  | BUS-303 | -12.027292   | -77.043013   | 2026-06-22 03:30:01 |
| 4  | BUS-101 | -12.035649   | -77.036419   | 2026-06-22 03:30:04 |
| …  | …       | …            | …            | …                   |

Con 3 buses enviando cada 3 segundos → se generan **60 registros por minuto**.

## Estructura de la tabla

```sql
CREATE TABLE ubicaciones (
    id        INT AUTO_INCREMENT PRIMARY KEY,  -- número único que crece solo
    id_bus    VARCHAR(20) NOT NULL,             -- nombre del bus (BUS-101, etc.)
    latitud   DECIMAL(10,8) NOT NULL,           -- coordenada norte-sur
    longitud  DECIMAL(11,8) NOT NULL,           -- coordenada este-oeste
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP -- fecha/hora automática
);
```

- `AUTO_INCREMENT` → el `id` se asigna solo, no hay que especificarlo
- `DEFAULT CURRENT_TIMESTAMP` → la fecha y hora se guardan automáticamente al insertar

---

# PARTE 5 — DASHBOARD WEB (dashboard.html)

## ¿Qué hace?

Una página web que muestra un mapa de Lima con los 3 buses moviéndose en tiempo real.

## ¿Cómo funciona?

```
Cada 3 segundos:
  1. JavaScript llama a: fetch('/buses')
  2. El servidor devuelve el JSON con posiciones
  3. JavaScript mueve los marcadores en el mapa
```

**Tecnologías usadas:**
- **Leaflet.js** → biblioteca para mapas interactivos (como Google Maps pero gratis y open source)
- **OpenStreetMap** → los mapas base (las calles de Lima)
- **fetch()** → función de JavaScript para pedir datos al servidor sin recargar la página

---

# PARTE 6 — SEGURIDAD: nftables (Firewall)

## ¿Qué hace?

Controla qué conexiones de red se permiten entrar al servidor.

## Regla principal: política DROP

```
policy DROP  →  bloquear TODO por defecto
```

Luego se abren solo los puertos necesarios:

| Puerto | ¿Para qué? |
|--------|-----------|
| 22     | SSH (administración remota) |
| 5050   | Buses → Servidor (TCP principal) |
| 5051   | Buses → Servidor (TCP respaldo) |
| 8080   | Dashboard → Servidor (HTTP principal) |
| 8081   | Dashboard → Servidor (HTTP respaldo) |
| 7070   | Navegador → Dashboard web |
| 9000   | HAProxy TCP |

**¿Por qué es importante?** Si alguien en internet intenta conectarse a un puerto no listado, el firewall lo bloquea automáticamente sin responder.

---

# PARTE 7 — ALTA DISPONIBILIDAD: HAProxy

## ¿Qué problema resuelve?

¿Qué pasa si el servidor principal se cae? Sin HAProxy, todos los buses perderían conexión.

## ¿Cómo funciona el failover?

```
Normal:
  Buses ──► HAProxy :9000 ──► ServidorATU :5050 (PRINCIPAL) ✓

Cuando el principal cae:
  HAProxy detecta la caída (health check cada 2s, 3 fallos = caído)
  Buses ──► HAProxy :9000 ──► ServidorATU :5051 (RESPALDO) ✓

Tiempo de recuperación: ~6 segundos
```

**¿Qué es un health check?**
HAProxy llama a `GET /health` cada 2 segundos. Si el servidor responde `OK`, está vivo. Si no responde 3 veces seguidas → se considera caído → tráfico al respaldo.

---

# PARTE 8 — ARCHIVO: dashboard2.html (Panel de Control)

## ¿Qué hace este archivo?

Es una segunda vista del mismo sistema, pensada para el **administrador** en vez del público general. En lugar de un mapa, muestra números, estados y un registro de eventos — como el panel de control de una torre de monitoreo.

## Explicación por partes

```html
<div class="grid-main">
    <div class="card kpi">
        <div class="valor" id="kpi-buses">—</div>
        <div class="label">Buses activos</div>
    </div>
    ...
</div>
```
**¿Qué son los KPI?**
KPI significa "indicador clave" (Key Performance Indicator). Aquí se muestran tres: buses activos, mensajes TCP recibidos y el uptime (tiempo que el servidor lleva encendido). Los números grandes se llenan con JavaScript, no están fijos en el HTML.

---

```javascript
async function verificarHealth(url, elementId) {
    try {
        const r = await fetch(url, { signal: AbortSignal.timeout(2000) });
        const el = document.getElementById(elementId);
        if (r.ok) {
            el.textContent = 'ACTIVO';
            el.className = 'nodo-status status-ok';
        } else {
            el.textContent = 'ERROR';
            el.className = 'nodo-status status-err';
        }
        return r.ok;
    } catch {
        el.textContent = 'CAÍDO';
        el.className = 'nodo-status status-err';
        return false;
    }
}
```
**¿Qué hace esto?**
Llama al endpoint `/health` de cada servidor (el principal en `:8080` y el respaldo en `:8081`), el mismo que usa HAProxy para decidir el failover. Si responde a tiempo, pinta el nodo de verde ("ACTIVO"); si tarda más de 2 segundos o falla, lo pinta de rojo ("CAÍDO"). Así el panel muestra en vivo lo mismo que HAProxy está verificando por detrás.

---

```javascript
async function actualizarStats() {
    const r = await fetch(API_STATS);
    const s = await r.json();
    document.getElementById('kpi-buses').textContent = s.buses_activos;
    document.getElementById('kpi-mensajes').textContent = s.mensajes_recibidos;
    document.getElementById('kpi-uptime').textContent = formatUptime(s.uptime_segundos);
    ...
}
```
**¿Qué hace esto?**
Llama al endpoint `/stats` de `ServidorATU.java` (el mismo que devuelve `puerto_tcp`, `mensajes_recibidos`, `uptime_segundos`, `buses_activos`) y actualiza los tres KPI. También compara el conteo de mensajes contra la lectura anterior para escribir una línea en el log de eventos, por ejemplo `+3 nuevos`.

---

```javascript
buses.forEach(bus => {
    const segs = Math.floor((Date.now() - new Date(bus.timestamp).getTime()) / 1000);
    const estado = segs < 10 ? 'pill-ok' : 'pill-warn';
    ...
});
```
**¿Qué hace esto?**
Por cada bus de la tabla, calcula hace cuántos segundos llegó su última señal GPS. Si fue hace menos de 10 segundos se marca "EN LÍNEA" (verde); si no, "TARDÍO" (amarillo). Esto avisa visualmente si un bus dejó de reportar, sin tener que revisar los logs a mano.

---

```javascript
async function ciclo() {
    await Promise.all([
        actualizarBuses(),
        actualizarStats(),
        verificarHealth(API_HEALTH1, 'arch-srv1'),
        verificarHealth(API_HEALTH2, 'arch-srv2'),
    ]);
    ...
}
setInterval(ciclo, 3000);
```
**¿Qué hace `Promise.all`?**
Lanza las cuatro peticiones (buses, stats, health principal, health respaldo) **al mismo tiempo** en vez de una tras otra, y espera a que todas terminen antes de seguir. Así el panel se actualiza cada 3 segundos sin que una petición lenta retrase a las demás.

---

# PARTE 9 — ARCHIVO: dashboard3.html (Consulta de Tiempo de Viaje)

## ¿Qué hace este archivo?

Simula la app que usaría un **pasajero**: elige en qué bus/ruta viajará, desde qué paradero hasta cuál, y el sistema le calcula cuánto falta para que llegue el bus y cuánto dura el viaje. Es la pieza que le faltaba al sistema para representar los tres roles típicos de una arquitectura de transporte: bus (cliente GPS), administrador (panel de control) y pasajero (esta consulta).

## Explicación por partes

```javascript
const RUTAS_PARADAS = {
    'BUS-101': { nombre: 'Av. Javier Prado (San Isidro → La Molina)', paradas: [
        { nombre: 'San Isidro (origen)', lat: -12.0956, lon: -77.0356 },
        { nombre: 'San Borja (intermedio)', lat: -12.0870, lon: -77.0000 },
        { nombre: 'La Molina (destino)', lat: -12.0780, lon: -76.9600 }
    ]},
    ...
};
```
**¿Qué es esto?**
Para cada uno de los 6 buses, se definieron 3 "paradas" con nombre (origen, intermedio, destino). Las coordenadas **no son inventadas**: son el primer punto, un punto de la mitad y el último punto de la misma ruta que ya usa `BusCliente.java` para simular el movimiento del bus. Así el paradero coincide con el camino real que recorre el bus en el mapa.

---

```javascript
function distanciaKm(lat1, lon1, lat2, lon2) {
    const R = 6371;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180) * Math.cos(lat2*Math.PI/180) * Math.sin(dLon/2)**2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}
```
**¿Qué es la fórmula de Haversine?**
Es la fórmula estándar para calcular la distancia entre dos coordenadas GPS (latitud/longitud) teniendo en cuenta que la Tierra es una esfera, no un plano. `R = 6371` es el radio de la Tierra en kilómetros. El resultado final es la distancia en línea recta entre los dos puntos.

---

```javascript
const FACTOR_SINUOSIDAD = 1.3;
const distViaje = distanciaKm(origen.lat, origen.lon, destino.lat, destino.lon) * FACTOR_SINUOSIDAD;
const tiempoViajeMin = (distViaje / velocidad) * 60;
```
**¿Por qué multiplicar por 1.3?**
La fórmula de Haversine da la distancia "en línea recta" (como el vuelo de un pájaro), pero un bus no vuela: sigue calles, cuadras y curvas. El factor `1.3` es una corrección simple para acercarse más a la distancia real que recorrería el bus por avenida. No es un cálculo de rutas real (como Google Maps), es una aproximación educativa.

---

```javascript
async function obtenerBusActual(idBus) {
    const r = await fetch(API_BUSES, { signal: AbortSignal.timeout(2500) });
    const buses = await r.json();
    return buses.find(b => b.id_bus === idBus) || null;
}
```
**¿Qué hace esto?**
Le pregunta al `ServidorATU` real (endpoint `/buses`, el mismo que usan los otros dos dashboards) dónde está el bus elegido **en este momento**. Si el servidor no responde, la función devuelve `undefined`; si responde pero el bus no está en la lista, devuelve `null`. Esta diferencia se usa después para mostrar un aviso distinto en cada caso.

---

```javascript
const distEspera = distanciaKm(posBus.lat, posBus.lon, origen.lat, origen.lon) * FACTOR_SINUOSIDAD;
const tiempoEsperaMin = (distEspera / velocidad) * 60;

const tiempoTotalMin = (tiempoEsperaMin || 0) + tiempoViajeMin;
const llegada = new Date(Date.now() + tiempoTotalMin * 60000);
```
**¿Qué hace esto paso a paso?**
1. Calcula qué tan lejos está el bus **ahora mismo** del paradero de origen elegido por el pasajero.
2. Con esa distancia y la velocidad promedio (elegida en el selector "Condición de tráfico": fluido 25 km/h, normal 18 km/h, congestionado 10 km/h), calcula el **tiempo de espera**.
3. Suma tiempo de espera + tiempo de viaje = **tiempo total**.
4. Le suma ese total a la hora actual para mostrar la **hora estimada de llegada**.

---

```javascript
capaLinea = L.polyline(puntosLinea, { color: '#ffc107', weight: 3, opacity: 0.7, dashArray: '6 6' }).addTo(mapa);
mapa.fitBounds(L.latLngBounds(puntosLinea), { padding: [30,30] });
```
**¿Qué hace esto?**
Dibuja en el mini-mapa una línea punteada amarilla entre la posición actual del bus, el paradero de origen y el paradero de destino, y ajusta el zoom (`fitBounds`) para que los tres puntos se vean completos en pantalla.

---

**Aviso importante que muestra el propio dashboard:** esta es una **simulación educativa**. Usa distancia entre coordenadas (no las calles reales), no considera semáforos, tránsito en tiempo real ni el trazado exacto de las avenidas. Sirve para demostrar el concepto de "consulta de tiempo de viaje" apoyándose en los datos GPS reales que ya produce el sistema, pero no reemplaza un motor de ruteo real.

---

# RESUMEN FINAL

| Componente | Archivo/Herramienta | Función |
|---|---|---|
| Bus (cliente) | `BusCliente.java` | Envía GPS al servidor cada 3s |
| Servidor central | `ServidorATU.java` | Recibe GPS, guarda en BD, sirve JSON |
| Base de datos | MariaDB | Almacena historial de ubicaciones |
| Mapa web | `dashboard.html` + Leaflet.js | Muestra buses en tiempo real |
| Panel de control | `dashboard2.html` | KPIs, health checks y log de eventos para el administrador |
| Consulta de viaje | `dashboard3.html` | Simula tiempo de espera y viaje para el pasajero |
| Firewall | nftables | Bloquea conexiones no autorizadas |
| Alta disponibilidad | HAProxy | Redirige al respaldo si el principal cae |

---

*Informe explicativo generado el 22/06/2026 — Proyecto ATU Monitor v1.0*  
*Actualizado el 05/07/2026 — se agregó la explicación de `dashboard2.html` y `dashboard3.html`*
