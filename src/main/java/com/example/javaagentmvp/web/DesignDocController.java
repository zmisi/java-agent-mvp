package com.example.javaagentmvp.web;

import com.example.javaagentmvp.dbagent.DesignDocumentNotFoundException;
import com.example.javaagentmvp.dbagent.DesignDocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/design-docs")
public class DesignDocController {

    private final DesignDocumentService designDocumentService;

    public DesignDocController(DesignDocumentService designDocumentService) {
        this.designDocumentService = designDocumentService;
    }

    @GetMapping
    public List<DesignDocSummaryDto> list() {
        return designDocumentService.listDocuments().stream()
                .map(d -> new DesignDocSummaryDto(d.relativePath(), d.fileName()))
                .toList();
    }

    @GetMapping("/content")
    public DesignDocContentDto content(@RequestParam("path") String path) {
        try {
            var doc = designDocumentService.readDocument(path);
            return new DesignDocContentDto(doc.relativePath(), doc.content());
        }
        catch (DesignDocumentNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DesignDocSummaryDto upload(@RequestParam("file") MultipartFile file) {
        try {
            var doc = designDocumentService.uploadDocument(file);
            return new DesignDocSummaryDto(doc.relativePath(), doc.fileName());
        }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    public record DesignDocSummaryDto(String relativePath, String fileName) {
    }

    public record DesignDocContentDto(String relativePath, String content) {
    }
}
