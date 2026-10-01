package io.github.coworking.booking.workspace;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** workspace-service as seen from booking-service; OpenFeign generates the implementation. */
@FeignClient(name = "workspace-service", url = "${services.workspace-service}")
public interface WorkspaceClient {

    /**
     * Throws {@code FeignException.NotFound} when there is no such workspace. The path variable is
     * named explicitly: Feign reads the annotation, not the parameter name.
     */
    @GetMapping("/workspaces/{id}")
    WorkspaceSummary get(@PathVariable("id") long id);

    /** Only the fields booking-service needs; unknown JSON properties are ignored. */
    class WorkspaceSummary {

        private long id;
        private String name;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
