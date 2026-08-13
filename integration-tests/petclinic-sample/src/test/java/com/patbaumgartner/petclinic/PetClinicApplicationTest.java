package com.patbaumgartner.petclinic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the sample over real HTTP. The build runs the optimizer's train and inject
 * goals before the test phase, so these requests run against the optimized
 * auto-configuration set and fail if the optimizer excluded something the application
 * needs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PetClinicApplicationTest {

	@LocalServerPort
	private int port;

	private final HttpClient httpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	/**
	 * Guards against this suite quietly proving nothing: without the training file on the
	 * classpath the application is not optimized and the assertions below stop covering
	 * the optimizer at all.
	 */
	@Test
	void theApplicationUnderTestIsOptimized() {
		assertThat(getClass().getClassLoader().getResource("META-INF/autoconfiguration-optimizer.properties"))
			.as("optimizer training file on the test classpath")
			.isNotNull();
	}

	@Test
	void listsOwners() throws Exception {
		HttpResponse<String> response = get("/owners");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("Owners");
	}

	@Test
	void filtersOwnersByRequestParameter() throws Exception {
		assertThat(get("/owners?lastName=Nobody").statusCode()).isEqualTo(200);
	}

	@Test
	void servesTheCreationForm() throws Exception {
		assertThat(get("/owners/new").statusCode()).isEqualTo(200);
	}

	@Test
	void createsAnOwnerAndShowsItByPathVariable() throws Exception {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("firstName", "Ada");
		form.put("lastName", "Lovelace");
		form.put("address", "1 Analytical Way");
		form.put("city", "London");
		form.put("telephone", "5551234");

		HttpResponse<String> created = post("/owners/new", form);

		assertThat(created.statusCode()).isEqualTo(302);
		String location = created.headers().firstValue("Location").orElseThrow();

		HttpResponse<String> details = get(URI.create(location).getPath());
		assertThat(details.statusCode()).isEqualTo(200);
		assertThat(details.body()).contains("Lovelace");
	}

	@Test
	void redisplaysTheFormForAnInvalidOwner() throws Exception {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("firstName", "");
		form.put("lastName", "");
		form.put("address", "");
		form.put("city", "");
		form.put("telephone", "");

		assertThat(post("/owners/new", form).statusCode()).isEqualTo(200);
	}

	@Test
	void exposesActuatorHealth() throws Exception {
		HttpResponse<String> response = get("/actuator/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
		return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
		String body = form.entrySet()
			.stream()
			.map((entry) -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
			.collect(Collectors.joining("&"));
		HttpRequest request = HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		return this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
