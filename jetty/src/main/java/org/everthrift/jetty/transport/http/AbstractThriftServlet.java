package org.everthrift.jetty.transport.http;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.NotImplementedException;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.eclipse.jetty.server.HttpOutput;
import org.everthrift.appserver.controller.AbstractThriftController;
import org.everthrift.appserver.controller.ThriftControllerInfo;
import org.everthrift.appserver.controller.ThriftProcessor;
import org.everthrift.appserver.controller.ThriftProtocolSupportIF;
import org.everthrift.appserver.utils.thrift.AbstractThriftClient;
import org.everthrift.appserver.utils.thrift.SessionIF;
import org.everthrift.clustering.MessageWrapper;
import org.everthrift.clustering.thrift.InvocationInfo;
import org.everthrift.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public abstract class AbstractThriftServlet extends HttpServlet implements InitializingBean {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(AbstractThriftServlet.class);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RpcHttpRegistry registry;

    private ThriftProcessor tp;

    protected abstract String getContentType();

    protected abstract TProtocolFactory getProtocolFactory();

    //    protected void service(HttpServletRequest req, HttpServletResponse resp)  throws ServletException, IOException {
    //
    //    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Access-Control-Allow-Headers, Authorization, X-Requested-With");
        super.doOptions(req, response);
    }

    static void out(AsyncContext asyncContext, HttpServletResponse response,  int status, String contentType, byte buf[]) throws IOException {
        out(asyncContext, response, status, contentType, buf, buf.length);
    }

    static void out(AsyncContext asyncContext, HttpServletResponse response, int status, String contentType, byte buf[], int length) throws IOException {

        response.setStatus(status);
        response.setContentType(contentType);
        response.setContentLength(length);
        response.setBufferSize(length);
        response.setHeader("X-Packet-Length", Integer.toString(length));
        final Checksum checksum = new CRC32();
        checksum.update(buf, 0, length);
        response.setHeader("X-Packet-CRC32", Long.toString(checksum.getValue()));

        final ServletOutputStream out = response.getOutputStream();

        final ByteArrayInputStream contentBA;
        final ByteBuffer contentBB;

        if (out instanceof HttpOutput){
            contentBB = ByteBuffer.wrap(buf, 0, length);
            contentBA = null;
        }else{
            contentBA = new ByteArrayInputStream(buf, 0, length);
            contentBB = null;
        }

        out.setWriteListener(new WriteListener() {
            @Override
            public void onWritePossible() throws IOException {

                if (log.isDebugEnabled())
                    log.debug("onWritePossible, out.class={}", out.getClass().getSimpleName());

                if (contentBB !=null){
                    while(out.isReady())
                    {
                        if (!contentBB.hasRemaining())
                        {
                            asyncContext.complete();
                            return;
                        }
                        ((HttpOutput)out).write(contentBB);
                    }
                }else{
                    final byte[] buffer = new byte[1024 * 4];

                    while(out.isReady()){
                        // read some content into the copy buffer
                        final int len=contentBA.read(buffer);

                        // If we are at EOF then complete
                        if (len < 0)    {
                            log.debug("complete");
                            asyncContext.complete();
                            return;
                        }

                        // write out the copy buffer. 
                        out.write(buffer,0,len);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                log.debug("Async Error",t);
                asyncContext.complete();
            }
        });
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {

        final AsyncContext asyncContext = request.startAsync();

        log.debug("Handle thrift request on THttpTransport");

        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Access-Control-Allow-Headers, Authorization, X-Requested-With");

        final Map<String, Object> attributes = Maps.newHashMap();
        attributes.put(MessageWrapper.HTTP_REQUEST_PARAMS, Optional.fromNullable(request.getParameterMap())
                                                                   .or(Collections.emptyMap()));
        attributes.put(MessageWrapper.HTTP_COOKIES, Optional.fromNullable(request.getCookies())
                                                            .or(() -> new Cookie[0]));
        attributes.put(MessageWrapper.HTTP_HEADERS,
                       Collections.list(request.getHeaderNames())
                                  .stream()
                                  .map(n -> Pair.create(n, request.getHeader(n)))
                                  .collect(Collectors.toMap(Pair::getFirst, Pair::getSecond)));

        final String xRealIp = request.getHeader(MessageWrapper.HTTP_X_REAL_IP);
        if (xRealIp != null) {
            attributes.put(MessageWrapper.HTTP_X_REAL_IP, xRealIp);
        } else {
            attributes.put(MessageWrapper.HTTP_X_REAL_IP, request.getRemoteHost() + ":" + request.getRemotePort());
        }

        final TMemoryInputTransport it = new TMemoryInputTransport(IOUtils.toByteArray(request.getInputStream()));

        final TProtocol in = getProtocolFactory().getProtocol(it);
        final TMessage tMessage;
        try {
            tMessage = in.readMessageBegin();
        } catch (TException e2) {
            out(asyncContext, response, 500, "text/plain", e2.getMessage().getBytes(StandardCharsets.UTF_8));
            return;
        }

        try {
            final TMemoryBuffer mw = tp.process(new ThriftProtocolSupportIF<TMemoryBuffer>() {

                @Override
                public String getSessionId() {
                    return null;
                }

                @Override
                public TMessage getTMessage() throws TException {
                    return tMessage;
                }

                @Override
                public Map<String, Object> getAttributes() {
                    return attributes;
                }

                @Override
                public <T extends TBase> T readArgs(ThriftControllerInfo tInfo) throws TException {

                    final TBase args = tInfo.makeArgument();
                    args.read(in);
                    in.readMessageEnd();

                    try {
                        final Method m = tInfo.getArgCls().getMethod("validate");
                        m.invoke(args);
                    } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException e1) {
                    } catch (InvocationTargetException e1) {
                        Throwables.propagateIfInstanceOf(e1.getCause(), TException.class);
                        throw Throwables.propagate(e1.getCause());
                    }

                    return (T) args;
                }

                @Override
                public void skip() throws TException {
                    TProtocolUtil.skip(in, TType.STRUCT);
                    in.readMessageEnd();
                }

                private TMemoryBuffer result(TApplicationException o) {
                    final TMemoryBuffer outT = new TMemoryBuffer(1024);
                    final TProtocol out = getProtocolFactory().getProtocol(outT);
                    try {
                        out.writeMessageBegin(new TMessage(tMessage.name, TMessageType.EXCEPTION, tMessage.seqid));
                        ((TApplicationException) o).write(out);
                        out.writeMessageEnd();
                        out.getTransport().flush(tMessage.seqid);
                    } catch (TException e) {
                        throw new RuntimeException(e);
                    }
                    return outT;
                }

                @Override
                public TMemoryBuffer result(final Object o, final ThriftControllerInfo tInfo) {

                    if (o instanceof TApplicationException) {
                        return result((TApplicationException) o);
                    } else if (o instanceof TProtocolException) {
                        return result(new TApplicationException(TApplicationException.PROTOCOL_ERROR, ((Exception) o).getMessage()));
                    } else if (o instanceof Exception && !(o instanceof TException)) {
                        return result(new TApplicationException(TApplicationException.INTERNAL_ERROR, ((Exception) o).getMessage()));
                    } else {
                        final TBase result;
                        try {
                            result = tInfo.makeResult(o);
                        } catch (TApplicationException e) {
                            return result(e);
                        }
                        final TMemoryBuffer outT = new TMemoryBuffer(1024);
                        final TProtocol out = getProtocolFactory().getProtocol(outT);

                        try {
                            out.writeMessageBegin(new TMessage(tMessage.name, TMessageType.REPLY, tMessage.seqid));
                            result.write(out);
                            out.writeMessageEnd();
                            out.getTransport().flush(tMessage.seqid);
                        } catch (TException e) {
                            throw new RuntimeException(e);
                        }

                        return outT;
                    }
                }

                @Override
                public void asyncResult(Object o, AbstractThriftController controller) {
                    final TMemoryBuffer tt = result(o, controller.getInfo());
                    try {
                        out(asyncContext, response, 200, getContentType(), tt.getArray(), tt.length());
                    } catch (IOException e) {
                        log.error("Async Error", e);
                    }

                    ThriftProcessor.logEnd(ThriftProcessor.log, controller, tMessage.name, getSessionId(), o);
                }

                @Override
                public boolean allowAsyncAnswer() {
                    return true;
                }

            }, new AbstractThriftClient<Object>(null) {

                private SessionIF session;

                @Override
                public boolean isThriftCallEnabled() {
                    return false;
                }

                @Override
                public void setSession(SessionIF data) {
                    session = data;
                }

                @Override
                public SessionIF getSession() {
                    return session;
                }

                @Override
                public String getSessionId() {
                    return null;
                }

                @Override
                public String getClientIp() {
                    final String xRealIp = request.getHeader(MessageWrapper.HTTP_X_REAL_IP);
                    if (xRealIp != null) {
                        return xRealIp;
                    } else {
                        return request.getRemoteHost() + ":" + request.getRemotePort();
                    }
                }

                @Override
                public void addCloseCallback(FutureCallback<Void> callback) {
                }

                @Override
                protected <T> CompletableFuture<T> thriftCall(Object sessionId, int timeout, InvocationInfo tInfo) throws TException {
                    throw new NotImplementedException();
                }
            });

            if (mw != null) {
                out(asyncContext, response, 200, getContentType(), mw.getArray(), mw.length());
            }
        } catch (Exception e) {
            out(asyncContext, response, 500, "text/plain", e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        tp = ThriftProcessor.create(context, registry);
    }

}
