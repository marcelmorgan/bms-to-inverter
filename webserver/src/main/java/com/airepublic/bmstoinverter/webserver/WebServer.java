package com.airepublic.bmstoinverter.webserver;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.core.PortAllocator;
import com.airepublic.bmstoinverter.core.bms.data.BatteryPack;
import com.airepublic.bmstoinverter.core.bms.data.EnergyStorage;
import com.airepublic.bmstoinverter.core.service.IWebServerService;
import com.airepublic.bmstoinverter.protocol.modbus.ModbusUtil;
import com.airepublic.bmstoinverter.protocol.modbus.ModbusUtil.RegisterCode;
import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.facade.ModbusSerialMaster;
import com.ghgande.j2mod.modbus.io.ModbusSerialTransaction;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class WebServer implements IWebServerService {
    private static Logger LOG = LoggerFactory.getLogger(WebServer.class);
    private Server server;
    private final String alarmMessages;
    private EnergyStorage energyStorage;
    private Supplier<String> statusSupplier;
    private final CopyOnWriteArrayList<javax.servlet.AsyncContext> sseClients = new CopyOnWriteArrayList<>();

    public WebServer() {
        final ResourceBundle bundle = ResourceBundle.getBundle("alarms");
        final Map<String, String> map = new LinkedHashMap<>();

        for (final String key : bundle.keySet()) {
            map.put(key, bundle.getString(key));
        }

        alarmMessages = new Gson().toJson(map);
    }


    @Override
    public void start(final int httpPort, final int httpsPort, final EnergyStorage energyStorage) {
        this.energyStorage = energyStorage;
        server = new Server();

        final HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecurePort(httpsPort);
        httpConfig.setSecureScheme("https");
        httpConfig.setSendServerVersion(false);
        httpConfig.setSendDateHeader(false);

        final ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        httpConnector.setPort(httpPort);
        server.addConnector(httpConnector);

        final SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(findKeyStore());
        sslContextFactory.setKeyStorePassword("changeit");
        sslContextFactory.setKeyManagerPassword("changeit");
        sslContextFactory.setWantClientAuth(true);
        sslContextFactory.setNeedClientAuth(false);

        final HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        final ServerConnector httpsConnector = new ServerConnector(server,
                new SslConnectionFactory(sslContextFactory, "http/1.1"),
                new HttpConnectionFactory(httpsConfig));
        httpsConnector.setPort(httpsPort);
        server.addConnector(httpsConnector);

        final SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionCookie("JSESSIONID");
        sessionHandler.setSessionIdPathParameterName("none");
        sessionHandler.setHttpOnly(true);
        sessionHandler.setSecureRequestOnly(true);

        final HandlerList handlers = new HandlerList();
        handlers.addHandler(new org.eclipse.jetty.server.handler.SecuredRedirectHandler());

        final ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(Resource.newClassPathResource("/static/"));
        resourceHandler.setDirectoriesListed(false);
        resourceHandler.setWelcomeFiles(new String[] { "index.html" });

        final AbstractHandler apiHandler = new AbstractHandler() {
            @Override
            public void handle(final String target, final org.eclipse.jetty.server.Request baseRequest,
                    final javax.servlet.http.HttpServletRequest request,
                    final javax.servlet.http.HttpServletResponse response) throws IOException, javax.servlet.ServletException {
                final String path = request.getRequestURI();

                if (path.equals("/favicon.ico")) {
                    final Resource favicon = Resource.newClassPathResource("static/favicon.ico");
                    if (favicon != null && favicon.exists()) {
                        response.setContentType("image/x-icon");
                        org.eclipse.jetty.util.IO.copy(favicon.getInputStream(), response.getOutputStream());
                        baseRequest.setHandled(true);
                    }
                    return;
                }

                if (path.equals("/data")) {
                    final String content = energyStorage.toJson();
                    response.setContentType("application/json; charset=utf-8");
                    response.setHeader("Access-Control-Allow-Origin", "http://localhost, https://localhost");
                    response.getWriter().write(content);
                    baseRequest.setHandled(true);
                    return;
                }

                if (path.equals("/alarmMessages")) {
                    response.setContentType("application/json; charset=utf-8");
                    response.setHeader("Access-Control-Allow-Origin", "http://localhost, https://localhost");
                    response.getWriter().write(alarmMessages);
                    baseRequest.setHandled(true);
                    return;
                }

                if (path.equals("/api/ports")) {
                    response.setContentType("application/json; charset=utf-8");
                    response.setHeader("Access-Control-Allow-Origin", "*");
                    response.getWriter().write(getPortsJson());
                    baseRequest.setHandled(true);
                    return;
                }

                if (path.equals("/api/status")) {
                    response.setContentType("application/json; charset=utf-8");
                    response.setHeader("Access-Control-Allow-Origin", "*");
                    final String status = statusSupplier != null ? statusSupplier.get() : "{\"bms\":[],\"inverter\":{}}";
                    response.getWriter().write(status);
                    baseRequest.setHandled(true);
                    return;
                }

                if (path.equals("/api/data/stream")) {
                    response.setContentType("text/event-stream");
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control", "no-cache");
                    response.setHeader("Connection", "keep-alive");
                    response.setHeader("Access-Control-Allow-Origin", "*");
                    if (energyStorage != null) {
                        final PrintWriter writer = response.getWriter();
                        writer.write("data: " + energyStorage.toJson() + "\n\n");
                        writer.flush();
                    }
                    final javax.servlet.AsyncContext async = request.startAsync();
                    async.setTimeout(0);
                    sseClients.add(async);
                    baseRequest.setHandled(true);
                    return;
                }

                if (path.equals("/api/scan/modbus") && "GET".equals(request.getMethod())) {
                    final String portParam = request.getParameter("port");
                    int baudRate = 9600;
                    int startId = 1;
                    int endId = 247;
                    try {
                        baudRate = Integer.parseInt(request.getParameter("baudRate"));
                    } catch (final Exception ignored) {
                    }
                    try {
                        startId = Integer.parseInt(request.getParameter("startId"));
                    } catch (final Exception ignored) {
                    }
                    try {
                        endId = Integer.parseInt(request.getParameter("endId"));
                    } catch (final Exception ignored) {
                    }
                    response.setContentType("text/event-stream");
                    response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control", "no-cache");
                    response.setHeader("Connection", "keep-alive");
                    response.setHeader("Access-Control-Allow-Origin", "*");
                    baseRequest.setHandled(true);
                    final PrintWriter sse = response.getWriter();
                    if (portParam == null || portParam.isEmpty()) {
                        sse.write("data: {\"type\":\"error\",\"message\":\"port parameter required\"}\n\n");
                        sse.flush();
                    } else {
                        scanModbusStreaming(portParam, baudRate, startId, endId, sse);
                    }
                    return;
                }

                if (path.equals("/api/config") && "GET".equals(request.getMethod())) {
                    response.setContentType("application/json; charset=utf-8");
                    response.setHeader("Access-Control-Allow-Origin", "*");
                    try {
                        response.getWriter().write(getConfigJson());
                    } catch (final Exception e) {
                        response.setStatus(500);
                        response.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
                    }
                    baseRequest.setHandled(true);
                    return;
                }

                if (path.equals("/api/config") && "POST".equals(request.getMethod())) {
                    final StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = request.getReader()) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            body.append(line);
                        }
                    }
                    try {
                        handlePostConfig(body.toString());
                        response.setStatus(200);
                        response.setContentType("application/json; charset=utf-8");
                        response.getWriter().write("{\"status\":\"restarting\"}");
                    } catch (final Exception e) {
                        response.setStatus(500);
                        response.setContentType("application/json; charset=utf-8");
                        response.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
                    }
                    baseRequest.setHandled(true);
                    // Restart after flushing the response
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (final InterruptedException ignored) {
                        }
                        System.exit(0);
                    }).start();
                    return;
                }
            }
        };

        final HandlerList contentHandlers = new HandlerList();
        contentHandlers.addHandler(resourceHandler);
        contentHandlers.addHandler(apiHandler);

        final String username = System.getProperty("webserver.username", "");
        final String password = System.getProperty("webserver.password", "");

        if (!username.trim().isEmpty() && !password.trim().isEmpty()) {
            final SecurityHandler securityHandler = createSecurityHandler(username, password);
            securityHandler.setHandler(contentHandlers);
            handlers.addHandler(securityHandler);
        } else {
            handlers.addHandler(contentHandlers);
        }

        sessionHandler.setHandler(handlers);
        server.setHandler(sessionHandler);

        LOG.info("Starting webserver on ports " + httpPort + ":" + httpsPort);
        try {
            server.start();
            LOG.info("Started webserver on ports " + httpPort + ":" + httpsPort + " successfully!");
        } catch (final Exception e) {
            LOG.error("FAILED to start webserver on ports " + httpPort + ":" + httpsPort + "!", e);
        }
    }


    @Override
    public void onDataUpdated(final String dataJson) {
        final Iterator<javax.servlet.AsyncContext> it = sseClients.iterator();
        while (it.hasNext()) {
            final javax.servlet.AsyncContext ctx = it.next();
            try {
                final PrintWriter writer = ctx.getResponse().getWriter();
                writer.write("data: " + dataJson + "\n\n");
                writer.flush();
                if (writer.checkError()) {
                    sseClients.remove(ctx);
                    try {
                        ctx.complete();
                    } catch (final Exception ignored) {
                    }
                }
            } catch (final Exception e) {
                sseClients.remove(ctx);
                try {
                    ctx.complete();
                } catch (final Exception ignored) {
                }
            }
        }
    }


    @Override
    public void setStatusSupplier(final Supplier<String> statusSupplier) {
        this.statusSupplier = statusSupplier;
    }


    private void scanModbusStreaming(final String portName, final int baudRate,
            final int startId, final int endId, final PrintWriter out) {
        final int total = endId - startId + 1;
        final String allocKey = findAllocatorKey(portName);

        if (allocKey != null) {
            LOG.info("Modbus scan (shared port) {}", allocKey);
            final Port port = PortAllocator.allocate(allocKey);
            try {
                port.clearBuffers();
                for (int unitId = startId; unitId <= endId; unitId++) {
                    if (!sseSend(out, "{\"type\":\"progress\",\"current\":" + unitId + ",\"total\":" + total + "}")) break;
                    try {
                        port.sendFrame(ModbusUtil.createRequestBuffer(RegisterCode.READ_HOLDING_REGISTERS, 0, 1, unitId));
                        if (port.receiveFrame() != null) {
                            if (!sseSend(out, "{\"type\":\"found\",\"address\":" + unitId + "}")) break;
                        }
                    } catch (final Exception ignored) {
                    }
                }
                sseSend(out, "{\"type\":\"done\",\"scanned\":" + total + "}");
            } catch (final Exception e) {
                sseSend(out, "{\"type\":\"error\",\"message\":\"" + escJson(e.getMessage()) + "\"}");
            } finally {
                PortAllocator.free(allocKey);
            }
        } else {
            // Port not managed by PortAllocator — open directly via jSerialComm.
            // j2mod's SerialConnection tries to set kernel RS485 mode which fails on FTDI
            // USB adapters; plain jSerialComm (same lib Python uses) works fine.
            LOG.info("Modbus scan (direct jSerialComm) {}", portName);
            com.fazecast.jSerialComm.SerialPort comPort = null;
            try {
                String devPath = portName;
                try {
                    final Path p = Paths.get(portName);
                    devPath = (Files.isSymbolicLink(p) ? p.toRealPath() : p).toString();
                } catch (final Exception ignored) {
                }

                comPort = com.fazecast.jSerialComm.SerialPort.getCommPort(devPath);
                comPort.setBaudRate(baudRate);
                comPort.setNumDataBits(8);
                comPort.setNumStopBits(com.fazecast.jSerialComm.SerialPort.ONE_STOP_BIT);
                comPort.setParity(com.fazecast.jSerialComm.SerialPort.NO_PARITY);
                comPort.setComPortTimeouts(
                        com.fazecast.jSerialComm.SerialPort.TIMEOUT_READ_BLOCKING, 300, 0);

                if (!comPort.openPort()) {
                    sseSend(out, "{\"type\":\"error\",\"message\":\"Could not open port " + escJson(devPath) + "\"}");
                    return;
                }

                for (int unitId = startId; unitId <= endId; unitId++) {
                    if (!sseSend(out, "{\"type\":\"progress\",\"current\":" + unitId + ",\"total\":" + total + "}")) break;
                    try {
                        final int avail = comPort.bytesAvailable();
                        if (avail > 0) {
                            comPort.readBytes(new byte[avail], avail);
                        }
                        comPort.writeBytes(modbusRtuRequest(unitId), 8);
                        final byte[] resp = new byte[1];
                        if (comPort.readBytes(resp, 1) > 0 && (resp[0] & 0xFF) == unitId) {
                            if (!sseSend(out, "{\"type\":\"found\",\"address\":" + unitId + "}")) break;
                        }
                    } catch (final Exception ignored) {
                    }
                }
                sseSend(out, "{\"type\":\"done\",\"scanned\":" + total + "}");
            } catch (final Exception e) {
                sseSend(out, "{\"type\":\"error\",\"message\":\"" + escJson(e.getMessage()) + "\"}");
            } finally {
                if (comPort != null && comPort.isOpen()) {
                    comPort.closePort();
                }
            }
        }
    }


    private boolean sseSend(final PrintWriter out, final String json) {
        out.write("data: " + json + "\n\n");
        out.flush();
        return !out.checkError();
    }


    private String escJson(final String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private String findAllocatorKey(final String portName) {
        if (PortAllocator.hasPort(portName)) return portName;

        try {
            final Path p = Paths.get(portName);
            // If user selected a by-id symlink, try the resolved real path too
            if (Files.isSymbolicLink(p)) {
                final String real = p.toRealPath().toString();
                if (PortAllocator.hasPort(real)) return real;
            }
            // If user selected /dev/ttyUSBX, scan by-id links that resolve to it
            final Path byId = Paths.get("/dev/serial/by-id");
            if (Files.isDirectory(byId)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(byId)) {
                    for (final Path link : stream) {
                        try {
                            if (PortAllocator.hasPort(link.toString()) &&
                                    link.toRealPath().equals(p.toRealPath())) {
                                return link.toString();
                            }
                        } catch (final Exception ignored) {
                        }
                    }
                }
            }
        } catch (final Exception ignored) {
        }

        return null;
    }


    private byte[] modbusRtuRequest(final int unitId) {
        // FC 03 — read 1 holding register at address 0x0000
        final byte[] frame = new byte[8];
        frame[0] = (byte) unitId;
        frame[1] = 0x03;
        frame[2] = 0x00;
        frame[3] = 0x00;
        frame[4] = 0x00;
        frame[5] = 0x01;
        int crc = 0xFFFF;
        for (int i = 0; i < 6; i++) {
            crc ^= frame[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        frame[6] = (byte) (crc & 0xFF);
        frame[7] = (byte) (crc >> 8);
        return frame;
    }




    private String getPortsJson() {
        final List<Map<String, String>> ports = new ArrayList<>();

        final Path byId = Paths.get("/dev/serial/by-id");
        if (Files.isDirectory(byId)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(byId)) {
                for (final Path link : stream) {
                    try {
                        final Path target = Files.readSymbolicLink(link);
                        final Path resolved = byId.resolve(target).normalize();
                        final Map<String, String> port = new LinkedHashMap<>();
                        port.put("path", link.toString());
                        port.put("resolvedTo", resolved.toString());
                        port.put("description", link.getFileName().toString());
                        ports.add(port);
                    } catch (final Exception ignored) {
                    }
                }
            } catch (final Exception ignored) {
            }
        }

        for (final String prefix : new String[] { "ttyUSB", "ttyACM", "ttyS" }) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("/dev"), prefix + "*")) {
                for (final Path p : stream) {
                    final String pathStr = p.toString();
                    final boolean alreadyListed = ports.stream()
                            .anyMatch(port -> pathStr.equals(port.get("resolvedTo")));
                    if (!alreadyListed) {
                        final Map<String, String> port = new LinkedHashMap<>();
                        port.put("path", pathStr);
                        port.put("resolvedTo", pathStr);
                        port.put("description", p.getFileName().toString());
                        ports.add(port);
                    }
                }
            } catch (final Exception ignored) {
            }
        }

        return new Gson().toJson(ports);
    }


    private String getConfigJson() throws Exception {
        final String configFile = System.getProperty("configFile", "config.properties");
        final Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
        }

        final TreeMap<Integer, LinkedHashMap<String, Object>> bmsMap = new TreeMap<>();
        for (final String key : props.stringPropertyNames()) {
            if (key.matches("bms\\.\\d+\\..*")) {
                final String[] parts = key.split("\\.", 3);
                final int id = Integer.parseInt(parts[1]);
                final String subKey = parts[2];
                final LinkedHashMap<String, Object> entry = bmsMap.computeIfAbsent(id, k -> {
                    final LinkedHashMap<String, Object> e = new LinkedHashMap<>();
                    e.put("id", k);
                    return e;
                });
                final String val = props.getProperty(key);
                try {
                    entry.put(subKey, Integer.parseInt(val));
                } catch (final NumberFormatException e) {
                    entry.put(subKey, val);
                }
            }
        }

        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("pollInterval", Integer.parseInt(props.getProperty("bms.pollInterval", "1")));
        } catch (final NumberFormatException e) {
            result.put("pollInterval", 1);
        }
        result.put("bms", new ArrayList<>(bmsMap.values()));

        final LinkedHashMap<String, Object> inverter = new LinkedHashMap<>();
        inverter.put("type", props.getProperty("inverter.type", ""));
        inverter.put("portLocator", props.getProperty("inverter.portLocator", ""));
        try {
            inverter.put("baudRate", Integer.parseInt(props.getProperty("inverter.baudRate", "500000")));
        } catch (final NumberFormatException e) {
            inverter.put("baudRate", 500000);
        }
        try {
            inverter.put("sendInterval", Integer.parseInt(props.getProperty("inverter.sendInterval", "1")));
        } catch (final NumberFormatException e) {
            inverter.put("sendInterval", 1);
        }
        result.put("inverter", inverter);

        return new Gson().toJson(result);
    }


    private void handlePostConfig(final String json) throws Exception {
        final String configFile = System.getProperty("configFile", "config.properties");

        final Properties existing = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            existing.load(fis);
        } catch (final Exception ignored) {
        }

        for (final String key : new ArrayList<>(existing.stringPropertyNames())) {
            if (key.matches("bms\\.\\d+\\..*") || key.equals("bms.pollInterval")
                    || key.startsWith("inverter.")) {
                existing.remove(key);
            }
        }

        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        if (root.has("pollInterval")) {
            existing.setProperty("bms.pollInterval", root.get("pollInterval").getAsString());
        }

        if (root.has("bms") && root.get("bms").isJsonArray()) {
            int idx = 1;
            for (final JsonElement el : root.getAsJsonArray("bms")) {
                final JsonObject bms = el.getAsJsonObject();
                final int id = bms.has("id") ? bms.get("id").getAsInt() : idx;
                for (final Map.Entry<String, JsonElement> entry : bms.entrySet()) {
                    if (!entry.getKey().equals("id")) {
                        existing.setProperty("bms." + id + "." + entry.getKey(),
                                entry.getValue().getAsString());
                    }
                }
                idx++;
            }
        }

        if (root.has("inverter") && root.get("inverter").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("inverter").entrySet()) {
                existing.setProperty("inverter." + entry.getKey(), entry.getValue().getAsString());
            }
        }

        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            existing.store(fos, "Updated by web UI");
        }
    }


    private static SecurityHandler createSecurityHandler(final String username, final String password) {
        final UserStore userStore = new UserStore();
        userStore.addUser(username, Credential.getCredential(password), new String[] { "user" });

        final HashLoginService loginService = new HashLoginService();
        loginService.setName("MyRealm");
        loginService.setUserStore(userStore);

        final ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.setLoginService(loginService);

        final FormAuthenticator formAuthenticator = new FormAuthenticator("/login.html", "/login.html?error=true", false) {
            @Override
            public org.eclipse.jetty.server.Authentication validateRequest(final javax.servlet.ServletRequest req,
                    final javax.servlet.ServletResponse res, final boolean mandatory)
                    throws org.eclipse.jetty.security.ServerAuthException {
                // If Jetty saved an API endpoint as the post-login redirect URL, replace
                // it with the root so the user lands on the dashboard instead.
                final javax.servlet.http.HttpSession session =
                        ((javax.servlet.http.HttpServletRequest) req).getSession(false);
                if (session != null) {
                    final String saved = (String) session.getAttribute("org.eclipse.jetty.security.form_URI");
                    if (saved != null && saved.contains("/api/")) {
                        session.setAttribute("org.eclipse.jetty.security.form_URI", "/");
                    }
                }
                return super.validateRequest(req, res, mandatory);
            }
        };
        security.setAuthenticator(formAuthenticator);

        Constraint constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[] { "user" });

        final ConstraintMapping cmLogin = new ConstraintMapping();
        cmLogin.setPathSpec("/login.html");
        cmLogin.setConstraint(constraint);
        constraint.setAuthenticate(false);

        final ConstraintMapping cmStatic = new ConstraintMapping();
        cmStatic.setPathSpec("/styles.css");
        cmStatic.setConstraint(constraint);
        constraint.setAuthenticate(false);

        constraint = new Constraint();
        constraint.setName("auth");
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[] { "user" });

        final ConstraintMapping cmDefault = new ConstraintMapping();
        cmDefault.setPathSpec("/*");
        cmDefault.setConstraint(constraint);

        security.setConstraintMappings(java.util.Arrays.asList(cmLogin, cmStatic, cmDefault));

        return security;
    }


    private String findKeyStore() {
        try {
            final java.io.InputStream keystoreStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("ssl/keystore.jks");
            if (keystoreStream == null) {
                throw new RuntimeException("Unable to read keystore.jks from resources");
            }

            final java.io.File tempFile = java.io.File.createTempFile("keystore", ".jks");
            tempFile.deleteOnExit();

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                final byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = keystoreStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            return tempFile.getAbsolutePath();
        } catch (final java.io.IOException e) {
            throw new RuntimeException("Failed to create temporary keystore file", e);
        }
    }


    @Override
    public void stop() {
        try {
            server.stop();
        } catch (final Exception e) {
            LOG.error("Errors stopping webserver: ", e);
        }
    }


    public static void main(final String[] args) throws Exception {
        System.setProperty("webserver.username", "username");
        System.setProperty("webserver.password", "password");
        final EnergyStorage energyStorage = new EnergyStorage();
        energyStorage.getBatteryPacks().add(new BatteryPack());
        energyStorage.getBatteryPacks().add(new BatteryPack());
        energyStorage.getBatteryPacks().add(new BatteryPack());
        energyStorage.getBatteryPacks().add(new BatteryPack());
        energyStorage.getBatteryPacks().add(new BatteryPack());
        new WebServer().start(8080, 8443, energyStorage);
    }
}
