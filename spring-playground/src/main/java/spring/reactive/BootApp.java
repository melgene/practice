package spring.reactive;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.streams.Pump;
import io.vertx.ext.reactivestreams.ReactiveReadStream;
import io.vertx.ext.reactivestreams.ReactiveWriteStream;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CookieHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.embedded.AbstractEmbeddedReactiveHttpServer;
import org.springframework.boot.context.embedded.EmbeddedReactiveHttpServer;
import org.springframework.boot.context.embedded.EmbeddedReactiveHttpServerCustomizer;
import org.springframework.boot.context.embedded.ReactiveHttpServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.HttpHandlerAdapterSupport;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Function;

@SpringBootApplication
public class BootApp {

    public static void main(String[] args) {
        SpringApplication.run(BootApp.class);
    }

//    @Bean
    public VertxEmbeddedHttpServerFactory vertxEmbeddedHttpServerFactory() {
        return new VertxEmbeddedHttpServerFactory();
    }

}

@Controller
class BootController {

    @GetMapping("/")
    public
    @ResponseBody
    String get() {
        System.out.println("IN GET METHOD");
        return "Hello reactive!";
    }

}

class VertxEmbeddedHttpServerFactory implements ReactiveHttpServerFactory {

    @Override
    public EmbeddedReactiveHttpServer getReactiveHttpServer(
            HttpHandler httpHandler,
            EmbeddedReactiveHttpServerCustomizer... embeddedReactiveHttpServerCustomizers) {
        VertxEmbeddedReactiveHttpServer server = new VertxEmbeddedReactiveHttpServer();
        server.setHandler(httpHandler);

        try {
            server.afterPropertiesSet();
            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

class VertxEmbeddedReactiveHttpServer extends AbstractEmbeddedReactiveHttpServer implements EmbeddedReactiveHttpServer {

    protected final Log logger = LogFactory.getLog(getClass());

    private Vertx vertx;

    private VertxHttpHandlerAdapter handler;

    private boolean running;

    @Override
    public void afterPropertiesSet() throws Exception {
        vertx = Vertx.vertx();
        logger.info("Vertx instance created");
        handler = new VertxHttpHandlerAdapter(getHttpHandler());
    }

    @Override
    public void start() {
        if (!running) {
            DeploymentOptions deploymentOptions = new DeploymentOptions();
            deploymentOptions.setWorker(true);
            vertx.deployVerticle(new ReactiveVerticle(), deploymentOptions);
            logger.info("Reactive verticle deployed");
            running = true;
        }
    }

    @Override
    public void stop() {
        if (running) {
            vertx.close();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private class ReactiveVerticle extends AbstractVerticle {
        @Override
        public void start() throws Exception {
            Router router = Router.router(vertx);
            router.route().handler(CookieHandler.create());
            router.route().handler(ctx -> {
                System.out.println("CONTEXT IS HERE");
                handler.apply(ctx);
            });
            HttpServerOptions httpServerOptions = new HttpServerOptions();
            httpServerOptions.setPort(getPort());
            if (getAddress() != null) {
                httpServerOptions.setHost(getAddress().getHostAddress());
            }
            logger.info(String.format("Vertx address: %s:%s", getAddress(), getPort()));
            vertx.createHttpServer(httpServerOptions).requestHandler(router::accept).listen();
            logger.info("Reactive verticle started");

        }
    }
}

class VertxHttpHandlerAdapter extends HttpHandlerAdapterSupport implements Function<RoutingContext, Mono<Void>> {

    public VertxHttpHandlerAdapter(HttpHandler httpHandler) {
        super(httpHandler);
    }

    public VertxHttpHandlerAdapter(Map<String, HttpHandler> handlerMap) {
        super(handlerMap);
    }

    @Override
    public Mono<Void> apply(RoutingContext routingContext) {
        NettyDataBufferFactory bufferFactory = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
        VertxServerHttpRequest request = new VertxServerHttpRequest(routingContext, bufferFactory);
        VertxServerHttpResponse response = new VertxServerHttpResponse(routingContext, bufferFactory);
        System.out.println("BEFORE HANDLING");
        return getHttpHandler().handle(request, response)
                .log()
                .otherwise(ex -> {
                    logger.info("Could not complete request", ex);
                    routingContext.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
                    return Mono.empty();
                })
                .doOnSuccess(aVoid -> logger.info("Successfully completed request"));
    }

}

class VertxServerHttpRequest extends AbstractServerHttpRequest {

    private final NettyDataBufferFactory bufferFactory;

    private final RoutingContext routingContext;

    private final HttpServerRequest request;

    public VertxServerHttpRequest(RoutingContext routingContext, NettyDataBufferFactory bufferFactory) {
        super(initUri(routingContext.request()), initHeaders(routingContext.request()));
        this.routingContext = routingContext;
        this.request = routingContext.request();
        this.bufferFactory = bufferFactory;
        System.out.println("INITED REQUEST");
    }

    private static URI initUri(HttpServerRequest request) {
        Assert.notNull(request, "Vertx 'httpServerRequest' must not be null");
        try {
            URI uri = new URI(request.uri());
            SocketAddress remoteAddress = request.remoteAddress();

            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    (remoteAddress != null ? remoteAddress.host() : null),
                    (remoteAddress != null ? remoteAddress.port() : -1),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Could not get URI: " + ex.getMessage(), ex);
        }
    }

    private static HttpHeaders initHeaders(HttpServerRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : request.headers().names()) {
            headers.put(name, request.headers().getAll(name));
        }
        return headers;
    }

    @Override
    protected MultiValueMap<String, HttpCookie> initCookies() {
        System.out.println("INIT COOKIES");
        MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
        for (Cookie cookie : routingContext.cookies()) {
            HttpCookie httpCookie = new HttpCookie(cookie.getName(), cookie.getValue());
            cookies.add(cookie.getName(), httpCookie);
        }
        return cookies;
    }

    @Override
    public HttpMethod getMethod() {
        System.out.println("GETTING METHOD");
        HttpMethod httpMethod = HttpMethod.valueOf(request.method().name());
        System.out.println("HTTP METHOD  " + httpMethod);
        return httpMethod;
    }

    @Override
    public Flux<DataBuffer> getBody() {
        System.out.println("GETTING BODY");
        ReactiveWriteStream<Buffer> rws = ReactiveWriteStream.writeStream(routingContext.vertx());
        Pump.pump(request, rws).start();
        return Flux.from(rws).log().map(buffer -> bufferFactory.wrap(buffer.getByteBuf()));
    }
}


class VertxServerHttpResponse extends AbstractServerHttpResponse {

    private final RoutingContext routingContext;

    private final HttpServerResponse response;

    public VertxServerHttpResponse(RoutingContext routingContext, DataBufferFactory dataBufferFactory) {
        super(dataBufferFactory);
        this.routingContext = routingContext;
        this.response = routingContext.response();
        System.out.println("INITED RESPONSE");
    }

    @Override
    protected Mono<Void> writeWithInternal(Publisher<DataBuffer> publisher) {
        System.out.println("writeWithInternal");
        Flux<Buffer> body = toBuffers(publisher).log();
        return writeWithInternal(body, response).doOnNext(none -> response.end());
    }

    @Override
    protected Mono<Void> writeAndFlushWithInternal(Publisher<Publisher<DataBuffer>> publisher) {
        System.out.println("writeAndFlushWithInternal");
        return Flux.from(publisher)
                .log()
                .map(VertxServerHttpResponse::toBuffers)
                .doOnNext(chunk -> writeWithInternal(chunk, response).block())
                .doOnComplete(response::end)
                .then();
    }

    private static Mono<Void> writeWithInternal(Flux<Buffer> body, HttpServerResponse response) {
        ReactiveReadStream<Buffer> rrs = ReactiveReadStream.readStream();
        body.subscribe(rrs);
        body.doOnNext(e -> System.out.println("~~~~~~~~~~NEXT"));
        Pump.pump(rrs, response).start();
        return body.then();
    }

    @Override
    protected void applyStatusCode() {
        response.setStatusCode(getStatusCode().value());
    }

    @Override
    protected void applyHeaders() {
        HttpHeaders headers = getHeaders();
        for (String name : headers.keySet()) {
            for (String value : headers.get(name)) {
                response.putHeader(name, value);
            }
        }
    }

    @Override
    protected void applyCookies() {
        for (String name : getCookies().keySet()) {
            for (ResponseCookie httpCookie : getCookies().get(name)) {
                Cookie cookie = Cookie.cookie(name, httpCookie.getValue());
                if (!httpCookie.getMaxAge().isNegative()) {
                    cookie.setMaxAge(httpCookie.getMaxAge().getSeconds());
                }
                httpCookie.getDomain().ifPresent(cookie::setDomain);
                httpCookie.getPath().ifPresent(cookie::setPath);
                cookie.setSecure(httpCookie.isSecure());
                cookie.setHttpOnly(httpCookie.isHttpOnly());
                routingContext.addCookie(cookie);
            }
        }
    }

    private static Flux<Buffer> toBuffers(Publisher<DataBuffer> dataBuffers) {
        return Flux.from(dataBuffers).map(b -> Buffer.buffer(NettyDataBufferFactory.toByteBuf(b)));
    }
}