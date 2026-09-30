package io.github.coworking.workspace;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/workspaces")
public class WorkspaceController {

    private final WorkspaceRepository workspaces;

    public WorkspaceController(WorkspaceRepository workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping
    public List<WorkspaceResponse> list() {
        return workspaces.findAll(Sort.by("id")).stream().map(WorkspaceResponse::from).toList();
    }

    @GetMapping("/{id}")
    public WorkspaceResponse get(@PathVariable long id) {
        return workspaces.findById(id)
                .map(WorkspaceResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace " + id + " not found"));
    }

    public record WorkspaceResponse(long id, String name, String address, int capacity) {

        static WorkspaceResponse from(Workspace workspace) {
            return new WorkspaceResponse(
                    workspace.getId(), workspace.getName(), workspace.getAddress(), workspace.getCapacity());
        }
    }
}
