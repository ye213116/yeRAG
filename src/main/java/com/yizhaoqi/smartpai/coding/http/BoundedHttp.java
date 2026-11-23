package com.yizhaoqi.smartpai.coding.http;

import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds the entire response body and deadline, including slow or never-ending streams. */
public final class BoundedHttp {
    private BoundedHttp() {}
    public static HttpResponse<byte[]> send(HttpClient client,HttpRequest request,int limit,Duration timeout) throws IOException,InterruptedException {
        var future=client.sendAsync(request,info -> new Body(limit));
        try { return future.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch(TimeoutException expired) { future.cancel(true);throw new java.net.SocketTimeoutException("HTTP operation deadline exceeded"); }
        catch(ExecutionException failed) { throw new IOException("HTTP response unavailable",failed.getCause()); }
        catch(InterruptedException interrupted) { future.cancel(true);throw interrupted; }
    }
    private static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private long received;
        private boolean ended;
        Body(int limit) { this.limit=limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value;delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            if(ended) return;
            for(var buffer:buffers) received+=buffer.remaining();
            if(received>limit) {ended=true;subscription.cancel();delegate.onError(new IOException("HTTP body exceeded limit"));}
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if(!ended) {ended=true;delegate.onError(error);} }
        public void onComplete() { if(!ended) {ended=true;delegate.onComplete();} }
    }
}
