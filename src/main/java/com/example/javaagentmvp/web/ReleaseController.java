package com.example.javaagentmvp.web;

import com.example.javaagentmvp.dbagent.ReleaseService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping
    public List<ReleaseService.ReleaseSummary> list() {
        return releaseService.listReleases();
    }

    @GetMapping("/suggested-id")
    public SuggestedReleaseIdDto suggestedId() {
        return new SuggestedReleaseIdDto(releaseService.suggestReleaseId());
    }

    @GetMapping("/{releaseId}")
    public ReleaseService.ReleaseDetail get(@PathVariable String releaseId) {
        return releaseService.getRelease(releaseId);
    }

    @PostMapping
    public ReleaseService.ReleaseDetail create(@RequestBody CreateReleaseDto body) {
        if (body == null || body.designDocPath() == null || body.designDocPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "designDocPath is required");
        }
        if (body.title() == null || body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        return releaseService.createFromDesignDoc(body.designDocPath(), body.title());
    }

    @PutMapping("/{releaseId}/scripts/{scriptId}")
    public ReleaseService.ReleaseScriptView updateScript(
            @PathVariable String releaseId,
            @PathVariable long scriptId,
            @RequestBody UpdateScriptDto body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        return releaseService.updateScriptSql(releaseId, scriptId, body.sqlContent());
    }

    @PostMapping("/{releaseId}/scripts/{scriptId}/submit-review")
    public ReleaseService.ReleaseScriptView submitReview(
            @PathVariable String releaseId,
            @PathVariable long scriptId) {
        return releaseService.submitForReview(releaseId, scriptId);
    }

    @PostMapping("/{releaseId}/scripts/{scriptId}/review")
    public ReleaseService.ReleaseScriptView review(
            @PathVariable String releaseId,
            @PathVariable long scriptId,
            @RequestBody ReviewScriptDto body) {
        if (body == null || body.action() == null || body.action().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required (approve or reject)");
        }
        boolean approve = switch (body.action().strip().toLowerCase()) {
            case "approve" -> true;
            case "reject" -> false;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action must be approve or reject");
        };
        return releaseService.reviewScript(releaseId, scriptId, approve, body.comment());
    }

    @PostMapping("/{releaseId}/deploy-test")
    public ReleaseService.DeploymentView deployTest(@PathVariable String releaseId) {
        return releaseService.deployToTest(releaseId);
    }

    public record CreateReleaseDto(String designDocPath, String title) {
    }

    public record UpdateScriptDto(String sqlContent) {
    }

    public record ReviewScriptDto(String action, String comment) {
    }

    public record SuggestedReleaseIdDto(String suggestedId) {
    }
}
