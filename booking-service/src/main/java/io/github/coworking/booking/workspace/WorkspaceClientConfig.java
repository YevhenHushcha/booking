package io.github.coworking.booking.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
class WorkspaceClientConfig {

    /**
     * Built on Boot's {@link RestClient.Builder}, not {@code RestClient.create()}: the auto-configured
     * builder carries the observation registry, so the call gets a span and a {@code traceparent}
     * header, and it applies the {@code spring.http.client.*} timeouts.
     */
    @Bean
    WorkspaceClient workspaceClient(RestClient.Builder builder, @Value("${services.workspace-service}") String baseUrl) {
        RestClient restClient = builder.baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(WorkspaceClient.class);
    }
}
