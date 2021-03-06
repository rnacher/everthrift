package org.everthrift.clustering.jgroups;

import org.apache.thrift.TException;
import org.everthrift.clustering.thrift.InvocationInfo;
import org.everthrift.clustering.thrift.InvocationInfoThreadHolder;
import org.jgroups.Address;
import org.jgroups.blocks.ResponseMode;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ClusterThriftClientIF {

    public static interface Reply<T> {
        T get() throws TException;

        default T getUnchecked() {
            try {
                return get();
            } catch (TException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class Options {
        private Options() {
        }

        public static Timeout timeout(int timeout) {
            return new Timeout(timeout);
        }

        public static Loopback loopback(boolean loopback) {
            return new Loopback(loopback);
        }

        public static Response responseMode(ResponseMode responseMode) {
            return new Response(responseMode);
        }
    }

    public static class Timeout extends Options {
        public final int timeout;

        private Timeout(int timeout) {
            this.timeout = timeout;
        }
    }

    public static class Loopback extends Options {
        public final boolean loopback;

        private Loopback(boolean loopback) {
            this.loopback = loopback;
        }
    }

    public static class Response extends Options {
        public final ResponseMode responseMode;

        private Response(ResponseMode responseMode) {
            this.responseMode = responseMode;
        }
    }

    default public <T> CompletableFuture<Map<Address, Reply<T>>> call(T methodCall, Options... options) throws TException {
        return call(InvocationInfoThreadHolder.getInvocationInfo(), options);
    }

    @SuppressWarnings("rawtypes")
    default public <T> CompletableFuture<Map<Address, Reply<T>>> call(final InvocationInfo ii, Options... options) throws TException {
        return call(null, null, ii, options);
    }

    default public <T> CompletableFuture<T> call(Address destination, T methodCall, Options... options) throws TException {
        return call(destination, InvocationInfoThreadHolder.getInvocationInfo(), options);
    }

    @SuppressWarnings("rawtypes")
    default public <T> CompletableFuture<T> call(Address destination, InvocationInfo ii, Options... options) throws TException {
        final CompletableFuture<Map<Address, Reply<T>>> ret = call(Collections.singleton(destination), null, ii, options);

        final CompletableFuture<T> s = new CompletableFuture();

        ret.whenComplete((m, t) -> {
           if (t !=null){
               s.completeExceptionally(t);
           }else{
               try {
                   s.complete(m.get(destination).get());
               } catch (TException e) {
                   s.completeExceptionally(e);
               }
           }
        });

        return s;
    }

    @SuppressWarnings("rawtypes")
    public <T> CompletableFuture<Map<Address, Reply<T>>> call(Collection<Address> dest, Collection<Address> exclusionList,
                                                              InvocationInfo tInfo, Options... options) throws TException;

    default public <T> CompletableFuture<T> callOne(T methodCall, Options... options) throws TException {
        return callOne(InvocationInfoThreadHolder.getInvocationInfo(), options);
    }

    @SuppressWarnings("rawtypes")
    public <T> CompletableFuture<T> callOne(InvocationInfo ii, Options... options) throws TException;
}
