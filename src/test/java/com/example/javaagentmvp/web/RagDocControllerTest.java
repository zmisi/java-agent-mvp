package com.example.javaagentmvp.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagDocControllerTest {

    @Test
    void opensClasspathRagDoc() throws Exception {
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.getResource("classpath:rag-docs/hfut/sample.md")).thenReturn(resource);
        when(resource.exists()).thenReturn(true);
        when(resource.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        RagDocController controller = new RagDocController(resourceLoader);
        ResponseEntity<String> response = controller.open("rag-docs/hfut/sample.md");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("hello");
        assertThat(response.getHeaders().getContentType()).isNotNull();
    }

    @Test
    void redirectsForHttpSource() {
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        RagDocController controller = new RagDocController(resourceLoader);

        ResponseEntity<String> response = controller.open("https://example.com/doc.md");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/doc.md");
    }

    @Test
    void rejectsNonRagDocsPath() {
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        RagDocController controller = new RagDocController(resourceLoader);

        assertThatThrownBy(() -> controller.open("docs/other.md"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
