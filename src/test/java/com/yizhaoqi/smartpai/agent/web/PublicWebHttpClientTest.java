package com.yizhaoqi.smartpai.agent.web;

import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicWebHttpClientTest {
    @Test void rejectsNonPublicAddressRangesIncludingIpv6AndMappedIpv4() throws Exception {
        for (String ip : List.of("127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "198.18.0.1", "192.0.2.1", "224.0.0.1", "255.255.255.255", "::1", "fc00::1", "fe80::1",
                "::ffff:127.0.0.1", "2002:7f00:1::1", "2001:db8::1", "64:ff9b::7f00:1")) {
            assertFalse(PublicWebHttpClient.isPublicAddress(InetAddress.getByName(ip)), ip);
        }
        assertTrue(PublicWebHttpClient.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(PublicWebHttpClient.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test void rejectsUnsupportedSchemesCredentialsPortsAndLocalRequestsBeforeTransport() throws Exception {
        for (String url : List.of("file:///etc/passwd", "ftp://example.com/a", "http://user:password@example.com", "http://example.com:8080/a"))
            assertThrows(IllegalArgumentException.class, () -> PublicWebHttpClient.validateUrl(url));
        var transport = mock(CloseableHttpClient.class);
        try (var fixture = fixture(transport)) {
            assertThrows(java.io.IOException.class, () -> fixture.get(URI.create("http://127.0.0.1/"), Map.of(), true));
            verifyNoInteractions(transport);
        }
    }

    @Test void rechecksRedirectTargetsAndDoesNotForwardSearchCredentials() throws Exception {
        var transport = mock(CloseableHttpClient.class);
        var response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, 302, "Found"));
        when(response.getFirstHeader("Location")).thenReturn(new BasicHeader("Location", "http://169.254.169.254/latest/meta-data/"));
        when(transport.execute(any(HttpUriRequest.class))).thenReturn(response);
        try (var fixture = fixture(transport)) {
            assertThrows(java.io.IOException.class, () -> fixture.get(URI.create("https://8.8.8.8/"), Map.of(), true));
            verify(transport, times(1)).execute(any(HttpUriRequest.class));
        }
        reset(transport);
        when(transport.execute(any(HttpUriRequest.class))).thenReturn(response);
        try (var fixture = fixture(transport)) {
            assertThrows(java.io.IOException.class, () -> fixture.get(URI.create("https://8.8.8.8/"), Map.of("X-Subscription-Token", "secret"), false));
            verify(transport, times(1)).execute(any(HttpUriRequest.class));
        }
    }

    @Test void boundsTheDownloadedBodyEvenWhenContentLengthIsUnknown() throws Exception {
        var transport = mock(CloseableHttpClient.class);
        var response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK"));
        var entity = spy(new ByteArrayEntity(new byte[2048]));
        when(entity.getContentLength()).thenReturn(-1L);
        when(response.getEntity()).thenReturn(entity);
        when(transport.execute(any(HttpUriRequest.class))).thenReturn(response);
        var config = new WebProperties(); config.setMaxResponseBytes(1024);
        var client = new PublicWebHttpClient(config, transport);
        try { assertThrows(java.io.IOException.class, () -> client.get(URI.create("https://8.8.8.8/"), Map.of(), true)); }
        finally { client.close(); }
    }

    private Fixture fixture(CloseableHttpClient transport) { return new Fixture(transport); }
    static class Fixture extends PublicWebHttpClient implements AutoCloseable {
        Fixture(CloseableHttpClient transport) { super(new WebProperties(), transport); }
    }
}
