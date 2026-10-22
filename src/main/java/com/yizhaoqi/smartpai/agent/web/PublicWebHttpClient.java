package com.yizhaoqi.smartpai.agent.web;

import jakarta.annotation.PreDestroy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Public HTTP only. The DNS resolver checks the addresses actually used to connect. */
@Component
public class PublicWebHttpClient {
    public record Response(URI url, String contentType, byte[] body) { }
    private final WebProperties config;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "web-request-deadline");
        thread.setDaemon(true);
        return thread;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public PublicWebHttpClient(WebProperties config) {
        this(config, HttpClients.custom().setDnsResolver(PublicWebHttpClient::resolvePublic)
                .disableRedirectHandling().disableCookieManagement().disableAutomaticRetries()
                .setUserAgent("yeRAG/1.0 (public document reader)").build());
    }

    PublicWebHttpClient(WebProperties config, CloseableHttpClient client) {
        this.config = config;
        this.client = client;
    }

    public Response get(URI requested, Map<String, String> headers, boolean followRedirects) throws IOException {
        URI current = requested;
        long deadline = System.nanoTime() + config.getTimeout().toNanos();
        for (int redirects = 0; redirects <= 3; redirects++) {
            current = validateUrl(current.toString());
            // Also check literal IP hosts, for which HttpClient can bypass its DNS resolver.
            resolvePublic(current.getHost());
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("web request timed out");
            int timeout = (int) Math.min(Integer.MAX_VALUE, remaining);
            HttpGet request = new HttpGet(current);
            request.setConfig(RequestConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout)
                    .setConnectionRequestTimeout(timeout).build());
            headers.forEach(request::setHeader);
            var abort = deadlines.schedule(request::abort, remaining, TimeUnit.MILLISECONDS);
            try (var response = client.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                if (status >= 300 && status < 400) {
                    var location = response.getFirstHeader("Location");
                    if (!followRedirects || redirects == 3 || location == null) throw new IOException("web redirect rejected");
                    current = current.resolve(location.getValue());
                    continue;
                }
                if (status < 200 || status >= 300 || response.getEntity() == null)
                    throw new IOException("web request failed");
                var entity = response.getEntity();
                if (entity.getContentLength() > config.getMaxResponseBytes()) {
                    request.abort();
                    throw new IOException("web response too large");
                }
                byte[] body;
                try (var input = entity.getContent()) {
                    body = input.readNBytes(config.getMaxResponseBytes() + 1);
                    if (body.length > config.getMaxResponseBytes()) {
                        // Abort before closing the stream: a pooled connection must not drain an oversized body.
                        request.abort();
                        throw new IOException("web response too large");
                    }
                }
                var type = entity.getContentType();
                return new Response(current, type == null ? "" : type.getValue(), body);
            } finally {
                abort.cancel(false);
                request.releaseConnection();
            }
        }
        throw new IOException("too many web redirects");
    }

    public static URI validateUrl(String value) {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException("invalid public URL");
        final URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid public URL"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getHost().contains("%") || (uri.getPort() != -1 && uri.getPort() != ("https".equals(scheme) ? 443 : 80)))
            throw new IllegalArgumentException("only public HTTP(S) URLs on standard ports are supported");
        return uri;
    }

    static InetAddress[] resolvePublic(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new UnknownHostException("public host required");
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) throw new UnknownHostException("non-public address rejected");
        }
        return addresses;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 168 || b == 0 || (b == 88 && c == 99)))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113));
        }
        // Accept global unicast, excluding transition/documentation ranges.
        return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x02)
                && !(a == 0x20 && b == 0x01 && ((bytes[2] & 255) < 2
                || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)));
    }

    @PreDestroy public void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }
}
