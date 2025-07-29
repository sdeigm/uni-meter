package com.deigmueller.uni_meter.input.device.inexogy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.*;
import com.github.scribejava.core.oauth.OAuth10aService;
import com.github.scribejava.core.utils.StreamUtils;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Client for the Discovery API (<a href="https://api.discovergy.com/docs/">https://api.discovergy.com/docs/</a>)
 */
public class InexogyApiClient {
	// Instance members
	private final ObjectMapper objectMapper;
	private final String clientId;

	@Getter private final InexogyApi api;

	private final OAuth10aService authenticationService;
	private final OAuth1AccessToken accessToken;

	public InexogyApiClient(@NotNull ObjectMapper objectMapper,
													@NotNull String clientId,
													@NotNull String url,
													@NotNull String email,
													@NotNull String password) throws InterruptedException, ExecutionException, IOException {
		this(objectMapper, clientId, new InexogyApi(url, email, password));
	}

	public InexogyApiClient(ObjectMapper objectMapper, String clientId, InexogyApi api) throws InterruptedException, ExecutionException, IOException {
		this.objectMapper = objectMapper;
		this.api = api;
		this.clientId = clientId;
		Map<String, String> consumerTokenEntries = getConsumerToken();
		authenticationService = new ServiceBuilder(consumerTokenEntries.get("key")).apiSecret(consumerTokenEntries.get("secret")).build(api);
		OAuth1RequestToken requestToken = authenticationService.getRequestToken();
		String authorizationURL = authenticationService.getAuthorizationUrl(requestToken);
		String verifier = authorize(authorizationURL);
		accessToken = authenticationService.getAccessToken(requestToken, verifier);
	}

	public OAuthRequest createRequest(Verb verb, String endpoint) throws InterruptedException, ExecutionException, IOException {
		return new OAuthRequest(verb, api.getBaseAddress() + endpoint);
	}

	public Response executeRequest(OAuthRequest request) throws InterruptedException, ExecutionException, IOException {
		authenticationService.signRequest(accessToken, request);
		return authenticationService.execute(request);
	}

	public Response executeRequest(OAuthRequest request, int expectedStatusCode) throws InterruptedException, ExecutionException, IOException {
		Response response = executeRequest(request);
		if (response.getCode() != expectedStatusCode) {
			response.getBody();
			throw new RuntimeException("Status code is not " + expectedStatusCode + ": " + response);
		}
		return response;
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> getConsumerToken() throws IOException {
		byte[] rawRequest = ("client=" + clientId).getBytes(StandardCharsets.UTF_8);
		HttpURLConnection connection = getConnection(api.getBaseAddress() + "/oauth1/consumer_token", "POST", true, true);
		connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
		connection.setRequestProperty("Content-Length", Integer.toString(rawRequest.length));
		connection.connect();
		connection.getOutputStream().write(rawRequest);
		connection.getOutputStream().flush();
		String content = StreamUtils.getStreamContents(connection.getInputStream());
		connection.disconnect();
		
		return objectMapper.readValue(content, Map.class);
	}

	private static String authorize(String authorizationURL) throws IOException {
		HttpURLConnection connection = getConnection(authorizationURL, "GET", true, false);
		connection.connect();
		String content = StreamUtils.getStreamContents(connection.getInputStream());
		connection.disconnect();
		return content.substring(content.indexOf('=') + 1);
	}

	private static HttpURLConnection getConnection(String rawURL, String method, boolean doInput, boolean doOutput) throws IOException {
		URL url = new URL(rawURL);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setDoInput(doInput);
		connection.setDoOutput(doOutput);
		connection.setRequestMethod(method);
		connection.setRequestProperty("Accept", "*");
		connection.setInstanceFollowRedirects(false);
		connection.setRequestProperty("charset", "utf-8");
		connection.setUseCaches(false);
		return connection;
	}
}
