import java.io.*;
import java.net.*;
import java.util.*;

public class BusCliente {

    // Rutas reales de Lima/Callao: array de {lat, lon}
    static final Map<String, double[][]> RUTAS = new HashMap<>();
    static {
        // BUS-101: Av. Javier Prado (San Isidro → La Molina)
        RUTAS.put("BUS-101", new double[][]{
            {-12.0956, -77.0356}, {-12.0930, -77.0280}, {-12.0910, -77.0200},
            {-12.0890, -77.0100}, {-12.0870, -77.0000}, {-12.0850, -76.9900},
            {-12.0820, -76.9800}, {-12.0800, -76.9700}, {-12.0780, -76.9600}
        });
        // BUS-202: Av. Arequipa (Lima Centro → Miraflores)
        RUTAS.put("BUS-202", new double[][]{
            {-12.0464, -77.0310}, {-12.0550, -77.0310}, {-12.0650, -77.0310},
            {-12.0750, -77.0310}, {-12.0850, -77.0310}, {-12.0950, -77.0310},
            {-12.1050, -77.0310}, {-12.1150, -77.0310}, {-12.1200, -77.0290}
        });
        // BUS-303: Av. La Marina (San Miguel → Callao)
        RUTAS.put("BUS-303", new double[][]{
            {-12.0700, -77.0850}, {-12.0660, -77.0950}, {-12.0620, -77.1050},
            {-12.0580, -77.1150}, {-12.0540, -77.1250}, {-12.0500, -77.1350},
            {-12.0470, -77.1450}, {-12.0464, -77.1500}
        });
        // BUS-404: Av. Universitaria (Los Olivos → San Miguel)
        RUTAS.put("BUS-404", new double[][]{
            {-11.9800, -77.0620}, {-11.9950, -77.0620}, {-12.0100, -77.0620},
            {-12.0250, -77.0620}, {-12.0400, -77.0620}, {-12.0550, -77.0620},
            {-12.0700, -77.0700}, {-12.0800, -77.0780}
        });
        // BUS-505: Av. Brasil (Breña → Magdalena)
        RUTAS.put("BUS-505", new double[][]{
            {-12.0550, -77.0650}, {-12.0650, -77.0650}, {-12.0750, -77.0630},
            {-12.0850, -77.0610}, {-12.0950, -77.0580}, {-12.1000, -77.0560},
            {-12.1050, -77.0540}
        });
        // BUS-606: Av. Benavides (Miraflores → Surco)
        RUTAS.put("BUS-606", new double[][]{
            {-12.1150, -77.0290}, {-12.1180, -77.0200}, {-12.1200, -77.0100},
            {-12.1220, -77.0000}, {-12.1240, -76.9900}, {-12.1260, -76.9800},
            {-12.1280, -76.9700}, {-12.1300, -76.9600}
        });
    }

    public static void main(String[] args) throws Exception {
        String idBus = (args.length > 0) ? args[0] : "BUS-001";
        String host  = (args.length > 1) ? args[1] : "localhost";
        int    port  = (args.length > 2) ? Integer.parseInt(args[2]) : 5050;

        System.out.println("[" + idBus + "] Conectando a " + host + ":" + port);

        double[][] ruta = RUTAS.getOrDefault(idBus, RUTAS.get("BUS-101"));
        int totalPuntos = ruta.length;
        int indicePunto = 0;
        int direccion   = 1; // 1 = avanzar, -1 = retroceder (ida y vuelta)

        // Posición inicial = primer punto de la ruta
        double lat = ruta[0][0];
        double lon = ruta[0][1];

        try (Socket socket = new Socket(host, port);
             PrintWriter pw = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("[" + idBus + "] Conectado. Siguiendo ruta con " + totalPuntos + " puntos...");

            while (true) {
                double destinoLat = ruta[indicePunto][0];
                double destinoLon = ruta[indicePunto][1];

                // Mover gradualmente hacia el siguiente punto de la ruta
                double dLat = destinoLat - lat;
                double dLon = destinoLon - lon;
                double distancia = Math.sqrt(dLat * dLat + dLon * dLon);

                double paso = 0.0015; // ~150 metros por tick
                if (distancia < paso) {
                    lat = destinoLat;
                    lon = destinoLon;
                    // Avanzar al siguiente punto
                    indicePunto += direccion;
                    if (indicePunto >= totalPuntos) {
                        indicePunto = totalPuntos - 2;
                        direccion = -1;
                    } else if (indicePunto < 0) {
                        indicePunto = 1;
                        direccion = 1;
                    }
                } else {
                    lat += (dLat / distancia) * paso;
                    lon += (dLon / distancia) * paso;
                }

                String mensaje = idBus + "|" + String.format("%.6f", lat) + "|" + String.format("%.6f", lon);
                pw.println(mensaje);
                System.out.println("[" + idBus + "] Enviado: " + mensaje);

                String respuesta = br.readLine();
                System.out.println("[" + idBus + "] Respuesta: " + respuesta);

                Thread.sleep(3000);
            }
        } catch (ConnectException e) {
            System.err.println("[" + idBus + "] No se pudo conectar: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[" + idBus + "] Error: " + e.getMessage());
        }
    }
}
