package org.everthrift.jms;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoopbackJms {

    @Bean
    public RpcJmsRegistry RpcJmsRegistry() {
        return new RpcJmsRegistry();
    }

    @Bean
    public LocalJmsThriftClientServerImpl localJmsThriftClientServerImpl(@Qualifier("testMode") boolean testMode) {
        return new LocalJmsThriftClientServerImpl(testMode);
    }

}
