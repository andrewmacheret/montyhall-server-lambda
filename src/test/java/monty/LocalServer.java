package monty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import monty.services.MontyHallService;
import monty.store.InMemoryStore;

/**
 * Runs the Lambda handler on http://localhost:8080 with an in-memory store, for trying the client locally.
 * Start it with: ./mvnw -q test-compile exec:java -Dexec.mainClass=monty.LocalServer -Dexec.classpathScope=test
 */
public class LocalServer {
	public static void main(String[] args) throws IOException {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
		Handler handler = new Handler(new MontyHallService(new InMemoryStore()));

		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", exchange -> {
			try (exchange) {
				handle(handler, exchange);
			}
		});
		server.start();
		System.out.println("Listening on http://localhost:" + port);
	}

	private static synchronized void handle(Handler handler, HttpExchange exchange) throws IOException {
		// API Gateway does CORS in production, so fake it here
		exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
		exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
		if (exchange.getRequestMethod().equals("OPTIONS")) {
			exchange.sendResponseHeaders(204, -1);
			return;
		}

		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
				.withRawPath(path)
				.withBody(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
				.withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
						.withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod(method).withPath(path).build())
						.build())
				.build();
		APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

		byte[] body = response.getBody().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(response.getStatusCode(), body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}
}
