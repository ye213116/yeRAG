package com.yizhaoqi.smartpai.coding.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpTest {
    @Test void oversizedAndSlowBodiesAreCancelledBeforeTheyCanExhaustAnExecutionBudget() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/large",exchange -> {
            try { exchange.sendResponseHeaders(200,1024);exchange.getResponseBody().write(new byte[1024]); } finally { exchange.close(); }
        });
        server.createContext("/slow",exchange -> {
            try {
                exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();
                Thread.sleep(500);exchange.getResponseBody().write(2);
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client=HttpClient.newHttpClient();String base="http://localhost:"+server.getAddress().getPort();
            assertThrows(java.io.IOException.class,() -> BoundedHttp.send(client,HttpRequest.newBuilder(URI.create(base+"/large")).build(),32,Duration.ofSeconds(2)));
            assertThrows(java.net.SocketTimeoutException.class,() -> BoundedHttp.send(client,HttpRequest.newBuilder(URI.create(base+"/slow")).build(),32,Duration.ofMillis(100)));
        } finally { server.stop(0); }
    }
}
