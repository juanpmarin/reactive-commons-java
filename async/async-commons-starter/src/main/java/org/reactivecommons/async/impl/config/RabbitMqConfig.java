package org.reactivecommons.async.impl.config;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.java.Log;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.config.props.BrokerConfigProps;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.impl.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.impl.converters.json.ObjectMapperSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

import java.time.Duration;
import java.util.logging.Level;

@Log
@Configuration
@EnableConfigurationProperties(RabbitProperties.class)
@Import(BrokerConfigProps.class)
public class RabbitMqConfig {

    private static final String LISTENER_TYPE = "listener";

    private static final String SENDER_TYPE = "sender";


    @Value("${app.async.flux.maxConcurrency:250}")
    private Integer maxConcurrency;

    @Value("${spring.application.name}")
    private String appName;

    @Bean
    public ReactiveMessageSender messageSender(ConnectionFactoryProvider provider, MessageConverter converter,
                                               BrokerConfigProps brokerConfigProps, RabbitProperties rabbitProperties) {
        final Mono<Connection> senderConnection =
                createConnectionMono(provider.getConnectionFactory(), appName, SENDER_TYPE);
        final ChannelPoolOptions channelPoolOptions = new ChannelPoolOptions();
        final PropertyMapper map = PropertyMapper.get();

        map.from(rabbitProperties.getCache().getChannel()::getSize).whenNonNull()
                .to(channelPoolOptions::maxCacheSize);

        final ChannelPool channelPool = ChannelPoolFactory.createChannelPool(
                senderConnection,
                channelPoolOptions
        );

        final Sender sender = RabbitFlux.createSender(new SenderOptions()
                .channelPool(channelPool)
                .resourceManagementChannelMono(channelPool.getChannelMono()));

        return new ReactiveMessageSender(sender, brokerConfigProps.getAppName(), converter, new TopologyCreator(sender));
    }

    @Bean
    public ReactiveMessageListener messageListener(ConnectionFactoryProvider provider) {
        final Mono<Connection> connection =
                createConnectionMono(provider.getConnectionFactory(), appName, LISTENER_TYPE);
        final Receiver receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connection));
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(connection));

        return new ReactiveMessageListener(receiver, new TopologyCreator(sender), maxConcurrency);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectionFactoryProvider connectionFactory(RabbitProperties properties) {
        final ConnectionFactory factory = new ConnectionFactory();
        PropertyMapper map = PropertyMapper.get();
        map.from(properties::determineHost).whenNonNull().to(factory::setHost);
        map.from(properties::determinePort).to(factory::setPort);
        map.from(properties::determineUsername).whenNonNull().to(factory::setUsername);
        map.from(properties::determinePassword).whenNonNull().to(factory::setPassword);
        map.from(properties::determineVirtualHost).whenNonNull().to(factory::setVirtualHost);
        factory.useNio();
        return () -> factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapperSupplier objectMapperSupplier() {
        return new DefaultObjectMapperSupplier();
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new JacksonMessageConverter(objectMapperSupplier.get());
    }

    Mono<Connection> createConnectionMono(ConnectionFactory factory, String connectionPrefix, String connectionType) {
        return Mono.fromCallable(() -> factory.newConnection(connectionPrefix + " " + connectionType))
                .doOnError(err ->
                        log.log(Level.SEVERE, "Error creating connection to RabbitMq Broker. Starting retry process...", err)
                )
                .retryBackoff(Long.MAX_VALUE, Duration.ofMillis(300), Duration.ofMillis(3000))
                .cache();
    }

}
