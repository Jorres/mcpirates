package com.mcpirates.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcpirates.MCPirates;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Localhost JSON-RPC bridge for Claude (Tier 2). Dev-only — disabled in production.
 *
 * <p>Each request is a single line of JSON: {@code {"id":1,"method":"name","params":{...}}}.
 * Response: {@code {"id":1,"result":{...}}} or {@code {"id":1,"error":{"message":...}}}.
 * Handlers hop to the server thread via {@link MinecraftServer#execute} before touching state.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class BridgeServer {

    public static final int PORT = 25580;
    private static final int ACCEPT_TIMEOUT_MS = 500;
    private static final long HANDLER_TIMEOUT_MS = 5000;

    private static final AtomicReference<ServerSocket> SOCKET = new AtomicReference<>();
    private static volatile Thread acceptThread;
    private static volatile MinecraftServer server;

    private BridgeServer() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (FMLEnvironment.production) return;
        server = event.getServer();
        try {
            ServerSocket sock = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
            sock.setSoTimeout(ACCEPT_TIMEOUT_MS);
            SOCKET.set(sock);
            acceptThread = new Thread(BridgeServer::acceptLoop, "mcpirates-bridge-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            MCPirates.LOGGER.info("Claude bridge listening on 127.0.0.1:{}", PORT);
        } catch (IOException e) {
            MCPirates.LOGGER.error("Claude bridge failed to bind :{}: {}", PORT, e.toString());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerSocket sock = SOCKET.getAndSet(null);
        if (sock != null) {
            try { sock.close(); } catch (IOException ignored) {}
        }
        server = null;
    }

    private static void acceptLoop() {
        while (true) {
            ServerSocket sock = SOCKET.get();
            if (sock == null || sock.isClosed()) return;
            try {
                Socket client = sock.accept();
                Thread t = new Thread(() -> handleClient(client), "mcpirates-bridge-client");
                t.setDaemon(true);
                t.start();
            } catch (java.net.SocketTimeoutException ignored) {
                // poll for shutdown
            } catch (IOException e) {
                if (SOCKET.get() != null) {
                    MCPirates.LOGGER.warn("Claude bridge accept error: {}", e.toString());
                }
                return;
            }
        }
    }

    private static void handleClient(Socket client) {
        try (Socket c = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             Writer out = new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                String response = dispatch(line);
                out.write(response);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            MCPirates.LOGGER.debug("bridge client disconnected: {}", e.toString());
        }
    }

    private static String dispatch(String line) {
        JsonObject req;
        Object id = null;
        try {
            req = JsonParser.parseString(line).getAsJsonObject();
            if (req.has("id")) id = req.get("id").isJsonPrimitive()
                    ? (req.get("id").getAsJsonPrimitive().isNumber()
                        ? req.get("id").getAsInt() : req.get("id").getAsString())
                    : null;
        } catch (Exception e) {
            return errorResponse(null, "bad json: " + e.getMessage());
        }
        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();

        try {
            JsonObject result = runOnServerThread(() -> BridgeHandlers.dispatch(method, params, server));
            JsonObject envelope = new JsonObject();
            envelope.add("id", req.has("id") ? req.get("id") : com.google.gson.JsonNull.INSTANCE);
            envelope.add("result", result);
            return envelope.toString();
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            return errorResponse(id, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static JsonObject runOnServerThread(java.util.concurrent.Callable<JsonObject> task)
            throws Exception {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("server not running");
        if (s.isSameThread()) return task.call();
        CompletableFuture<JsonObject> fut = new CompletableFuture<>();
        s.execute(() -> {
            try { fut.complete(task.call()); }
            catch (Throwable t) { fut.completeExceptionally(t); }
        });
        try {
            return fut.get(HANDLER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("handler timeout " + HANDLER_TIMEOUT_MS + "ms");
        }
    }

    private static String errorResponse(Object id, String message) {
        JsonObject env = new JsonObject();
        if (id instanceof Number n) env.addProperty("id", n);
        else if (id instanceof String s) env.addProperty("id", s);
        else env.add("id", com.google.gson.JsonNull.INSTANCE);
        JsonObject err = new JsonObject();
        err.addProperty("message", message);
        env.add("error", err);
        return env.toString();
    }
}
