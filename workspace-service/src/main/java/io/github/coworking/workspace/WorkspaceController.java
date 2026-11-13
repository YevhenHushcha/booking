package io.github.coworking.workspace;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/workspaces")
public class WorkspaceController {

    private final WorkspaceRepository workspaces;

    public WorkspaceController(WorkspaceRepository workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping
    public List<WorkspaceResponse> list() {
        return workspaces.findAll(Sort.by("id")).stream().map(WorkspaceResponse::from).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public WorkspaceResponse get(@PathVariable long id) {
        return workspaces.findById(id)
                .map(WorkspaceResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace " + id + " not found"));
    }

    public static class WorkspaceResponse {

        private final long id;
        private final String name;
        private final String address;
        private final int capacity;

        WorkspaceResponse(long id, String name, String address, int capacity) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.capacity = capacity;
        }

        static WorkspaceResponse from(Workspace workspace) {
            return new WorkspaceResponse(
                    workspace.getId(), workspace.getName(), workspace.getAddress(), workspace.getCapacity());
        }

        public long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getAddress() {
            return address;
        }

        public int getCapacity() {
            return capacity;
        }
    }
}
