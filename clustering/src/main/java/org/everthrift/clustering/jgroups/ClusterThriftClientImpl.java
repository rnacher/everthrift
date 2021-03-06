package org.everthrift.clustering.jgroups;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.everthrift.clustering.thrift.InvocationInfo;
import org.everthrift.services.thrift.cluster.Node;
import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.blocks.ResponseMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

public abstract class ClusterThriftClientImpl implements ClusterThriftClientIF {

    public static interface TRunnable<T> {
        T run() throws TException;
    }

    public static class ReplyImpl<T> implements Reply<T> {
        private T v;

        private TException e;

        public ReplyImpl(TRunnable<T> r) {
            try {
                v = r.run();
            } catch (TException e) {
                this.e = e;
            }
        }

        @Override
        public T get() throws TException {
            if (e != null) {
                throw e;
            } else {
                return v;
            }
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((e == null) ? 0 : e.hashCode());
            result = prime * result + ((v == null) ? 0 : v.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ReplyImpl other = (ReplyImpl) obj;
            if (e == null) {
                if (other.e != null) {
                    return false;
                }
            } else if (!e.equals(other.e)) {
                return false;
            }
            if (v == null) {
                if (other.v != null) {
                    return false;
                }
            } else if (!v.equals(other.v)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            if (e != null) {
                return e.toString();
            } else if (v != null) {
                return v.toString();
            }

            return "<null>";
        }

    }

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private int defaultTimeout = 1000;

    private boolean defaultLoopback = false;

    private ResponseMode defaultResponseMode = ResponseMode.GET_ALL;

    protected final NodeDb nodeDb = new NodeDb();

    protected int getTimeout(Options[] options) {

        if (options != null) {
            for (Options o : options) {
                if (o instanceof Timeout) {
                    return ((Timeout) o).timeout;
                }
            }
        }
        return defaultTimeout;
    }

    protected boolean isLoopback(Options[] options) {

        if (options != null) {
            for (Options o : options) {
                if (o instanceof Loopback) {
                    return ((Loopback) o).loopback;
                }
            }
        }
        return defaultLoopback;
    }

    protected ResponseMode getResponseMode(Options[] options) {
        if (options != null) {
            for (Options o : options) {
                if (o instanceof Response) {
                    return ((Response) o).responseMode;
                }
            }
        }
        return defaultResponseMode;
    }

    public int getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(int defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public boolean isDefaultLoopback() {
        return defaultLoopback;
    }

    public void setDefaultLoopback(boolean defaultLoopback) {
        this.defaultLoopback = defaultLoopback;
    }

    public ResponseMode getDefaultResponseMode() {
        return defaultResponseMode;
    }

    public void setDefaultResponseMode(ResponseMode defaultResponseMode) {
        this.defaultResponseMode = defaultResponseMode;
    }

    public abstract JChannel getCluster();

    public void setNode(Address addr, Node node) {
        log.info("setNode(addr={}, node={})", addr, node);
        nodeDb.addNode(addr, node);
        nodeDb.retain(getCluster().getView().getMembers());
    }

    private <T> void _callOne(Iterator<Address> it, CompletableFuture<T> f, InvocationInfo ii, Options[] options) {
        if (!it.hasNext()) {
            f.completeExceptionally(new TApplicationException("all nodes failed"));
            return;
        }

        final Address a = it.next();
        log.debug("Try call {} on {}", ii.fullMethodName, a);

        try {
            this.<T>call(Collections.singletonList(a), null, ii, options).whenComplete((result, t) -> {

                if (t !=null) {
                    log.debug("Failed call {} on {}", ii.fullMethodName, a);
                    nodeDb.failed(a);
                    _callOne(it, f, ii, options);
                }else {
                    if (!result.containsKey(a)) {
                        log.debug("Failed call {} on {}", ii.fullMethodName, a);
                        nodeDb.failed(a);
                        _callOne(it, f, ii, options);
                    } else {
                        log.debug("Success call {} on {}", ii.fullMethodName, a);
                        nodeDb.success(a);
                        try {
                            f.complete(result.get(a).get());
                        } catch (TException e) {
                            f.completeExceptionally(e);
                        }
                    }
                }
            });
        } catch (TException e) {
            log.debug("Failed call {} on {}", ii.fullMethodName, a);
            nodeDb.failed(a);
            _callOne(it, f, ii, options);
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public <T> CompletableFuture<T> callOne(InvocationInfo ii, Options... options) throws TException {
        final CompletableFuture<T> f = new CompletableFuture();
        _callOne(nodeDb.getNode(ii.fullMethodName).iterator(), f, ii, options);
        return f;
    }
}
