package ru.greatstep.clientapp;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.TEXT_HTML;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.DefaultUriBuilderFactory.EncodingMode;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Component
@RequiredArgsConstructor
public class WebClientHelper {

    private final ObjectMapper objectMapper;
    private final Logger logger;

    public WebClient getWebClient(String url) {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(url);
        factory.setEncodingMode(EncodingMode.NONE);
        return getBuilder()
                .uriBuilderFactory(factory)
                .exchangeStrategies(exchangeStrategies())
                .build();
    }

    public Builder getBuilder() {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("myConnectionPool")
                .maxConnections(100_000)
                .pendingAcquireMaxCount(100_000)
                .build();
        ReactorClientHttpConnector clientHttpConnector = new ReactorClientHttpConnector(
                HttpClient.create(connectionProvider)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1_000_000)
                        .doOnConnected(c -> c.addHandlerLast(new ReadTimeoutHandler(1_000_000, TimeUnit.MILLISECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(1_000_000, TimeUnit.MILLISECONDS))));
        return WebClient.builder()
                .clientConnector(clientHttpConnector)
                .codecs(clientDefaultCodecsConfigurer -> {
                    clientDefaultCodecsConfigurer.defaultCodecs()
                            .jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
                    clientDefaultCodecsConfigurer.defaultCodecs().maxInMemorySize(500 * 1024);
                    clientDefaultCodecsConfigurer.defaultCodecs()
                            .jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));
                })
                .exchangeStrategies(exchangeStrategies());
    }

    private ExchangeStrategies exchangeStrategies() {
        return ExchangeStrategies
                .builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(500 * 1024);
                    configurer.customCodecs().register(new Jackson2JsonEncoder(new ObjectMapper(), TEXT_HTML));
                    configurer.customCodecs().register(new Jackson2JsonDecoder(new ObjectMapper(), TEXT_HTML));
                })
                .build();
    }

    public <T> Mono<T> getRequest(String url,
                                  Map<String, Object> params,
                                  Class<T> responseClass) {
        return getWebClient(url)
                .get()
                .uri(addParam(params))
                .header(CONTENT_TYPE, String.valueOf(APPLICATION_JSON))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::createFacadeWebClientException)
                .bodyToMono(responseClass)
                .doOnSuccess(httpSuccessConsumer());
    }

    private Consumer<Object> httpSuccessConsumer() {
        return response -> logger.finest(String.format("Successfully received response response = %s", response));
    }

    public Function<UriBuilder, URI> addParam(Map<String, Object> params) {
        return uriBuilder -> uriBuilder
                .queryParams(new LinkedMultiValueMap<>(params.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey,
                                e -> Collections.singletonList(
                                        URLEncoder.encode(e.getValue().toString(), StandardCharsets.UTF_8)))))).build();
    }

    public Mono<RuntimeException> createFacadeWebClientException(ClientResponse clientResponse) {
        return clientResponse.toEntity(String.class)
                .map(responseEntity ->
                        new RuntimeException(String.valueOf(responseEntity)));
    }

}
