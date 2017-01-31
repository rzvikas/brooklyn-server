/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.core.effector.http;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.config.MapConfigKey;
import org.apache.brooklyn.core.effector.AddEffector;
import org.apache.brooklyn.core.effector.EffectorBody;
import org.apache.brooklyn.core.effector.Effectors.EffectorBuilder;
import org.apache.brooklyn.core.entity.EntityInitializers;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.collections.Jsonya;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.http.executor.HttpConfig;
import org.apache.brooklyn.util.http.executor.HttpExecutor;
import org.apache.brooklyn.util.http.executor.HttpRequest;
import org.apache.brooklyn.util.http.executor.HttpResponse;
import org.apache.brooklyn.util.http.executor.UsernamePassword;
import org.apache.brooklyn.util.http.executor.apacheclient.HttpExecutorImpl;

import com.google.common.base.Enums;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;
import com.jayway.jsonpath.JsonPath;

public final class HttpCommandEffector extends AddEffector {

    public static final ConfigKey<String> EFFECTOR_URI = ConfigKeys.newStringConfigKey("uri");
    public static final ConfigKey<String> EFFECTOR_HTTP_VERB = ConfigKeys.newStringConfigKey("httpVerb");
    public static final ConfigKey<String> EFFECTOR_HTTP_USERNAME = ConfigKeys.newStringConfigKey("httpUsername");
    public static final ConfigKey<String> EFFECTOR_HTTP_PASSWORD = ConfigKeys.newStringConfigKey("httpPassword");
    public static final ConfigKey<Map<String, String>> EFFECTOR_HTTP_HEADERS = new MapConfigKey(String.class, "headers");
    public static final ConfigKey<Map<String, Object>> EFFECTOR_HTTP_PAYLOAD = new MapConfigKey(String.class, "httpPayload");
    public static final ConfigKey<String> JSON_PATH = ConfigKeys.newStringConfigKey("jsonPath", "JSON path to select in HTTP response");
    public static final ConfigKey<String> PUBLISH_SENSOR = ConfigKeys.newStringConfigKey("publishSensor", "Sensor name where to store json path extracted value");

    private enum HttpVerb {
        GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE;
    }

    public HttpCommandEffector(ConfigBag params) {
        super(newEffectorBuilder(params).build());
    }

    public static EffectorBuilder<String> newEffectorBuilder(ConfigBag params) {
        EffectorBuilder<String> eff = AddEffector.newEffectorBuilder(String.class, params);
        eff.impl(new Body(eff.buildAbstract(), params));
        return eff;
    }

    protected static class Body extends EffectorBody<String> {
        private final Effector<?> effector;
        private final ConfigBag params;

        public Body(Effector<?> eff, final ConfigBag params) {
            this.effector = eff;
            Preconditions.checkNotNull(params.getAllConfigRaw().get(EFFECTOR_URI.getName()), "uri must be supplied when defining this effector");
            Preconditions.checkNotNull(params.getAllConfigRaw().get(EFFECTOR_HTTP_VERB.getName()), "HTTP verb must be supplied when defining this effector");
            this.params = params;
        }

        @Override
        public String call(final ConfigBag params) {
            ConfigBag allConfig = ConfigBag.newInstanceCopying(this.params).putAll(params);
            final String uri = EntityInitializers.resolve(allConfig, EFFECTOR_URI);
            final String httpVerb = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_VERB);
            final String httpUsername = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_USERNAME);
            final String httpPassword = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_PASSWORD);
            final Map<String, String> headers = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_HEADERS);
            final Map<String, Object> payload = EntityInitializers.resolve(allConfig, EFFECTOR_HTTP_PAYLOAD);
            final String jsonPath = EntityInitializers.resolve(allConfig, JSON_PATH);
            final String publishSensor = EntityInitializers.resolve(allConfig, PUBLISH_SENSOR);
            Task t = Tasks.builder().displayName(effector.getName()).body(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    HttpExecutor httpExecutor = HttpExecutorImpl.newInstance();

                    String body = getBodyFromPayload(payload, headers);
                    HttpRequest request = buildHttpRequest(httpVerb, uri, headers, httpUsername, httpPassword, body);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try {
                        HttpResponse response = httpExecutor.execute(request);
                        validateResponse(response);
                        ByteStreams.copy(response.getContent(), out);
                        return new String(out.toByteArray());
                    } catch (IOException e) {
                        throw Exceptions.propagate(e);
                    }
                }
            }).build();

            String responseBody = (String) queue(t).getUnchecked();

            if (jsonPath == null) return responseBody;
            String extractedValue = JsonPath.parse(responseBody).read(jsonPath, String.class);
            if (publishSensor != null) {
                entity().sensors().set(Sensors.newStringSensor(publishSensor), extractedValue);
            }
            return extractedValue;
        }

        private void validateResponse(HttpResponse response) {
            int statusCode = response.code();
            if (statusCode == 401) {
                throw new RuntimeException("Authorization exception");
            } else if (statusCode == 404) {
                throw new RuntimeException("Resource not found");
            } else if (statusCode >= 500) {
                throw new RuntimeException("Server error");
            }
        }

        private HttpRequest buildHttpRequest(String httpVerb, String url, Map<String, String> headers, String httpUsername, String httpPassword, String body) throws MalformedURLException, URISyntaxException {
            // validate url string
            URI uri = new URL(url).toURI();
            // validate HTTP verb
            validateHttpVerb(httpVerb);
            HttpRequest.Builder httpRequestBuilder = new HttpRequest.Builder()
                    .body(body.getBytes())
                    .uri(uri)
                    .method(httpVerb)
                    .config(HttpConfig.builder()
                            .trustSelfSigned(true)
                            .trustAll(true)
                            .laxRedirect(true)
                            .build());

            if (headers != null) {
                httpRequestBuilder.headers(headers);
            }

            if (httpUsername != null && httpPassword != null) {
                httpRequestBuilder.credentials(new UsernamePassword(httpUsername, httpPassword));
            }

            return httpRequestBuilder.build();
        }

        private void validateHttpVerb(String httpVerb) {
            Optional<HttpVerb> state = Enums.getIfPresent(HttpVerb.class, httpVerb.toUpperCase());
            checkArgument(state.isPresent(), "Expected one of %s but was %s", Joiner.on(',').join(HttpVerb.values()), httpVerb);
        }

        private String getBodyFromPayload(Map<String, Object> payload, Map<String, String> headers) {
            String body = "";
            if (payload != null && !payload.isEmpty() && headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                body = Jsonya.newInstance().put(payload).toString();
            }
            return body;
        }

    }
}
