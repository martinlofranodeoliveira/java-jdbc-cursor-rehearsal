package com.lofranotecnologia.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback TCP relay proxy that allows withholding responses or sending RST
 * to test socket-timeout and connection-poisoning scenarios.
 */
public final class LoopbackRelay implements AutoCloseable {
    private final int targetPort;
    private final ServerSocket serverSocket;
    private final int relayPort;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean withholdResponses = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Socket> activeSockets = new CopyOnWriteArrayList<>();

    public LoopbackRelay(int targetPort) throws IOException {
        this.targetPort = targetPort;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.relayPort = serverSocket.getLocalPort();
        startAcceptLoop();
    }

    public int getRelayPort() {
        return relayPort;
    }

    public void setWithholdResponses(boolean withhold) {
        this.withholdResponses.set(withhold);
    }

    public void resetAllActiveSockets() {
        for (Socket s : activeSockets) {
            try {
                s.setSoLinger(true, 0); // Send TCP RST
                s.close();
            } catch (IOException ignored) {}
        }
        activeSockets.clear();
    }

    private void startAcceptLoop() {
        executor.submit(() -> {
            while (running.get() && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    activeSockets.add(clientSocket);
                    Socket serverTargetSocket = new Socket("127.0.0.1", targetPort);
                    activeSockets.add(serverTargetSocket);

                    // Client -> Server
                    executor.submit(() -> pipe(clientSocket, serverTargetSocket, false));
                    // Server -> Client
                    executor.submit(() -> pipe(serverTargetSocket, clientSocket, true));
                } catch (IOException e) {
                    if (!running.get()) break;
                }
            }
        });
    }

    private void pipe(Socket inSock, Socket outSock, boolean isServerToClient) {
        try (InputStream in = inSock.getInputStream();
             OutputStream out = outSock.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while (running.get() && (n = in.read(buf)) != -1) {
                if (isServerToClient && withholdResponses.get()) {
                    // Withhold server responses to trigger client socketTimeout
                    Thread.sleep(100);
                    continue;
                }
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { inSock.close(); } catch (IOException ignored) {}
            try { outSock.close(); } catch (IOException ignored) {}
            activeSockets.remove(inSock);
            activeSockets.remove(outSock);
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {}
        resetAllActiveSockets();
        executor.shutdownNow();
    }
}
