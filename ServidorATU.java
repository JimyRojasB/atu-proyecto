import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;

public class ServidorATU {

    static final String DB_URL  = "jdbc:mysql://localhost:3306/atu_monitoreo";
    static final String DB_USER = "atu_app";
    static final String DB_PASS = "atu_secure_2024";

    static int TCP_PORT  = 5050;
    static int HTTP_PORT = 8080;
    static long mensajesRecibidos = 0;
    static long tiempoInicio = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        if (args.length > 0) TCP_PORT  = Integer.parseInt(args[0]);
        if (args.length > 1) HTTP_PORT = Integer.parseInt(args[1]);

        System.out.println("[ATU] Servidor iniciando...");
        System.out.println("[ATU] TCP  puerto: " + TCP_PORT);
        System.out.println("[ATU] HTTP puerto: " + HTTP_PORT);

        // Hilo para servidor TCP
        new Thread(() -> iniciarTCP()).start();

        // Servidor HTTP
        iniciarHTTP();
    }

    static void iniciarTCP() {
        try (ServerSocket ss = new ServerSocket(TCP_PORT)) {
            System.out.println("[TCP] Escuchando en puerto " + TCP_PORT);
            ExecutorService pool = Executors.newCachedThreadPool();
            while (true) {
                Socket cliente = ss.accept();
                pool.submit(() -> manejarCliente(cliente));
            }
        } catch (Exception e) {
            System.err.println("[TCP] Error: " + e.getMessage());
        }
    }

    static void manejarCliente(Socket socket) {
        String ip = socket.getInetAddress().getHostAddress();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter pw = new PrintWriter(socket.getOutputStream(), true)) {

            String linea;
            while ((linea = br.readLine()) != null) {
                linea = linea.trim();
                System.out.println("[TCP] Recibido de " + ip + ": " + linea);
                String[] partes = linea.split("\\|");
                if (partes.length == 3) {
                    try {
                        String idBus   = partes[0].trim();
                        double latitud  = Double.parseDouble(partes[1].trim());
                        double longitud = Double.parseDouble(partes[2].trim());
                        guardarUbicacion(idBus, latitud, longitud);
                        mensajesRecibidos++;
                        pw.println("OK|" + idBus + "|" + System.currentTimeMillis());
                    } catch (NumberFormatException e) {
                        pw.println("ERROR|FORMATO_INVALIDO");
                    }
                } else {
                    pw.println("ERROR|SE_ESPERAN_3_CAMPOS");
                }
            }
        } catch (Exception e) {
            System.err.println("[TCP] Cliente " + ip + " desconectado: " + e.getMessage());
        }
    }

    static void guardarUbicacion(String idBus, double lat, double lon) {
        String sql = "INSERT INTO ubicaciones (id_bus, latitud, longitud) VALUES (?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE latitud=?, longitud=?, timestamp=CURRENT_TIMESTAMP";
        // Usamos upsert por id_bus en tabla de ultima_ubicacion alternativa
        String sqlInsert = "INSERT INTO ubicaciones (id_bus, latitud, longitud) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = conn.prepareStatement(sqlInsert)) {
            ps.setString(1, idBus);
            ps.setDouble(2, lat);
            ps.setDouble(3, lon);
            ps.executeUpdate();
            System.out.println("[DB] Guardado: Bus=" + idBus + " lat=" + lat + " lon=" + lon);
        } catch (SQLException e) {
            System.err.println("[DB] Error al guardar: " + e.getMessage());
        }
    }

    static void iniciarHTTP() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);

        // Endpoint /buses — últimas ubicaciones por bus
        server.createContext("/buses", exchange -> {
            String json = obtenerUltimasUbicaciones();
            byte[] respuesta = json.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, respuesta.length);
            exchange.getResponseBody().write(respuesta);
            exchange.getResponseBody().close();
            System.out.println("[HTTP] GET /buses -> " + respuesta.length + " bytes");
        });

        // Health check para HAProxy
        server.createContext("/health", exchange -> {
            byte[] ok = "OK".getBytes();
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.getResponseBody().close();
        });

        // Endpoint /stats — métricas del servidor
        server.createContext("/stats", exchange -> {
            long uptime = (System.currentTimeMillis() - tiempoInicio) / 1000;
            int totalBuses = obtenerTotalBuses();
            String json = String.format(
                "{\"puerto_tcp\":%d,\"puerto_http\":%d,\"mensajes_recibidos\":%d,\"uptime_segundos\":%d,\"buses_activos\":%d}",
                TCP_PORT, HTTP_PORT, mensajesRecibidos, uptime, totalBuses);
            byte[] respuesta = json.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, respuesta.length);
            exchange.getResponseBody().write(respuesta);
            exchange.getResponseBody().close();
        });

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[HTTP] Servidor HTTP activo en puerto " + HTTP_PORT);
    }

    static int obtenerTotalBuses() {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT id_bus) as total FROM ubicaciones")) {
            if (rs.next()) return rs.getInt("total");
        } catch (SQLException e) { }
        return 0;
    }

    static String obtenerUltimasUbicaciones() {
        String sql = "SELECT id_bus, latitud, longitud, MAX(timestamp) as ultima " +
                     "FROM ubicaciones GROUP BY id_bus ORDER BY ultima DESC";
        StringBuilder sb = new StringBuilder("[");
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            boolean primero = true;
            while (rs.next()) {
                if (!primero) sb.append(",");
                primero = false;
                sb.append(String.format(Locale.US,
                    "{\"id_bus\":\"%s\",\"latitud\":%f,\"longitud\":%f,\"timestamp\":\"%s\"}",
                    rs.getString("id_bus"),
                    rs.getDouble("latitud"),
                    rs.getDouble("longitud"),
                    rs.getString("ultima")));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error en consulta: " + e.getMessage());
            return "[]";
        }
        sb.append("]");
        return sb.toString();
    }
}
