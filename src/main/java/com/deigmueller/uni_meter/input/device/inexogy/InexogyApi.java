package com.deigmueller.uni_meter.input.device.inexogy;

import com.github.scribejava.core.builder.api.DefaultApi10a;
import com.github.scribejava.core.model.OAuth1RequestToken;
import lombok.Getter;

import java.net.URLEncoder;

import static java.nio.charset.StandardCharsets.UTF_8;

@Getter
public class InexogyApi extends DefaultApi10a {
	// Instance members
	private final String baseAddress;
	private final String user;
	private final String password;

	public InexogyApi(String baseAddress, String user, String password) {
		this.baseAddress = baseAddress;
		this.user = user;
		this.password = password;
	}

	@Override
	public String getRequestTokenEndpoint() {
		return baseAddress + "/oauth1/request_token";
	}

	@Override
	public String getAccessTokenEndpoint() {
		return baseAddress + "/oauth1/access_token";
	}

	@Override
	protected String getAuthorizationBaseUrl() {
		return "https://api.discovergy.com/public/v1";
	}

	@Override
	public String getAuthorizationUrl(OAuth1RequestToken requestToken) {
    return baseAddress + "/oauth1/authorize?oauth_token=" + requestToken.getToken() + "&email=" + URLEncoder.encode(user, UTF_8) + "&password="
        + URLEncoder.encode(password, UTF_8);
  }
}
