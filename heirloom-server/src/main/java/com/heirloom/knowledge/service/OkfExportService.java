package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class OkfExportService {

    private final KnowledgeSourceJpaRepository sourceJpa;

    public OkfExportService(KnowledgeSourceJpaRepository sourceJpa) {
        this.sourceJpa = sourceJpa;
    }

    /**
     * Stream knowledge files as a ZIP-compressed OKF bundle.
     * Reads directly from the file system (source of truth), preserving original Markdown.
     */
    public void exportToZip(String sourceFqn, HttpServletResponse response) throws IOException {
        KnowledgeSource source = sourceJpa.findByFullyQualifiedName(sourceFqn)
            .orElseThrow(() -> new IllegalArgumentException("Source not found: " + sourceFqn));

        Path root = Path.of(source.getPath());
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=knowledge-bundle.zip");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .forEach(p -> {
                    try {
                        String relPath = root.relativize(p).toString();
                        zos.putNextEntry(new ZipEntry(relPath));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        }
    }
}
