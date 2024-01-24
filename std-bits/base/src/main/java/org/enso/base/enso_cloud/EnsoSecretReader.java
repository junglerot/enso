package org.enso.base.enso_cloud;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/** * Internal class to read secrets from the Enso Cloud. */
class EnsoSecretReader {
  private static final Map<String, String> secrets = new HashMap<>();

  static void flushCache() {
    secrets.clear();
  }

  static void removeFromCache(String secretId) {
    secrets.remove(secretId);
  }

  /**
   * * Reads a secret from the Enso Cloud.
   *
   * @param secretId the ID of the secret to read.
   * @return the secret value.
   */
  static String readSecret(String secretId) {
    if (secrets.containsKey(secretId)) {
      return secrets.get(secretId);
    }

    var apiUri = AuthenticationProvider.getAPIRootURI() + "s3cr3tz/" + secretId;
    var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUri))
            .header("Authorization", "Bearer " + AuthenticationProvider.getToken())
            .GET()
            .build();

    HttpResponse<String> response;

    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException | InterruptedException e) {
      throw new IllegalArgumentException("Unable to read secret.");
    }

    if (response.statusCode() != 200) {
      throw new IllegalArgumentException("Unable to read secret.");
    }

    var secretJSON = response.body();
    var secretValue = readValueFromString(secretJSON);
    secrets.put(secretId, secretValue);
    return secretValue;
  }

  private static String readValueFromString(String json) {
    var base64 = json.substring(1, json.length() - 1).translateEscapes();
    return new String(
        java.util.Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
  }
}
