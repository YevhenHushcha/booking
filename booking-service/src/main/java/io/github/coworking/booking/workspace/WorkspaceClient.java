package io.github.coworking.booking.workspace;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * workspace-service as seen from booking-service. Spring generates the implementation from the
 * annotations, the way Feign does on the legacy branch.
 */
@HttpExchange("/workspaces")
public interface WorkspaceClient {

    /** Throws {@code HttpClientErrorException.NotFound} when there is no such workspace. */
    @GetExchange("/{id}")
    WorkspaceSummary get(@PathVariable long id);

    /** Only the fields booking-service needs; unknown JSON properties are ignored. */
    record WorkspaceSummary(long id, String name) {
    }
}
