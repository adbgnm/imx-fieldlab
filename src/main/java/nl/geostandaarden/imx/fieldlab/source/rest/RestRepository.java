package nl.geostandaarden.imx.fieldlab.source.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import nl.geostandaarden.imx.orchestrate.engine.OrchestrateException;
import nl.geostandaarden.imx.orchestrate.engine.exchange.CollectionRequest;
import nl.geostandaarden.imx.orchestrate.engine.exchange.DataRequest;
import nl.geostandaarden.imx.orchestrate.engine.exchange.ObjectRequest;
import nl.geostandaarden.imx.orchestrate.engine.source.DataRepository;
import nl.geostandaarden.imx.orchestrate.model.Path;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;

@RequiredArgsConstructor
public class RestRepository implements DataRepository {

  private final ObjectMapper jsonMapper = new JsonMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final HttpClient httpClient;

  private final Map<String, String> paths;

  @Override
  public Mono<Map<String, Object>> findOne(ObjectRequest request) {
    if ("Persoon".equals(request.getObjectType().getName())) {
      return getPersoon(request);
    }

    return httpClient.get()
        .uri(getObjectURI(request))
        .responseSingle((response, content) -> content.asInputStream())
        .map(this::parseResponse)
        .map(JsonMapFlattener::flatten)
        .onErrorComplete();
  }

  @Override
  public Flux<Map<String, Object>> find(CollectionRequest request) {
    if ("Persoon".equals(request.getObjectType().getName())) {
      throw new RuntimeException("Objecttype 'Persoon' does not support collection operations.");
    }

    var uri = getCollectionURI(request);

    if ("AdresseerbaarObject".equals(request.getObjectType().getName())) {
      var filter = request.getFilter();

      if (filter.getPath().equals(Path.fromString("heeftAlsHoofdadres"))) {
        uri = uri.concat("?nummeraanduidingIdentificatie=" + ((Map<String, String>) filter.getValue()).get("identificatie"));
      }
    }

    return httpClient.headers(builder -> builder.set("Accept-Crs", "epsg:28992"))
        .get()
        .uri(uri)
        .responseSingle((response, content) -> content.asInputStream())
        .map(this::parseResponse)
        .flatMapMany(resource -> Flux.fromIterable(mapCollection(resource)))
        .map(JsonMapFlattener::flatten)
        .onErrorComplete();
  }

  private Mono<Map<String, Object>> getPersoon(ObjectRequest request) {
    var bsn = request.getObjectKey().get("burgerservicenummer");

    var requestBody = new HashMap<String, Object>();
    requestBody.put("type", "RaadpleegMetBurgerservicenummer");
    requestBody.put("fields", List.of("naam"));
    requestBody.put("burgerservicenummer", List.of(bsn));

    return httpClient.headers(builder -> builder.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
        .post()
        .uri(getCollectionURI(request))
        .send(ByteBufFlux.fromString(Mono.just(writeString(requestBody))))
        .responseContent()
        .aggregate()
        .asString()
        .map(responseString -> {
          var responseBody = readString(responseString);
          var persoon = ((List<Map<String, Object>>) responseBody.get("personen")).get(0);
          var naam = (Map<String, Object>) persoon.get("naam");

          var result = new HashMap<String, Object>();
          result.put("burgerservicenummer", bsn);
          result.put("naam", naam.get("volledigeNaam"));
          return result;
        });
  }

  private Map<String, Object> readString(String data) {
    try {
      return jsonMapper.readValue(data, Map.class);
    } catch (IOException e) {
      throw new RuntimeException("Could not read JSON.", e);
    }
  }

  private String writeString(Object data) {
    try {
      return jsonMapper.writeValueAsString(data);
    } catch (IOException e) {
      throw new RuntimeException("Could not read JSON.", e);
    }
  }

  private String getObjectURI(ObjectRequest request) {
    var objectKey = request.getObjectKey()
        .values()
        .iterator()
        .next()
        .toString();

    return getCollectionURI(request)
        .concat("/" + objectKey);
  }

  private String getCollectionURI(DataRequest request) {
    var typeName = request.getObjectType()
        .getName();

    var path = Optional.ofNullable(paths.get(typeName))
        .orElse(typeName);

    return "/" + path;
  }

  private Map<String, Object> parseResponse(InputStream content) {
    try {
      return jsonMapper.readValue(content, Map.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private List<Map<String, Object>> mapCollection(Map<String, Object> resource) {
    if (resource.containsKey("_embedded")) {
      return ((Map<String, List<Map<String, Object>>>) resource.get("_embedded"))
          .values()
          .iterator()
          .next();
    }

    throw new OrchestrateException("Could not map collection resource");
  }
}
