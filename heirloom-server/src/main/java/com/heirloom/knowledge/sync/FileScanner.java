package com.heirloom.knowledge.sync;
import com.heirloom.knowledge.exception.SyncException;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
public class FileScanner {
    private static final Set<String> RESERVED = Set.of("index.md","log.md");
    private static final Pattern IGNORE = Pattern.compile("^\\\\.|node_modules|target");
    public Map<String,String> scan(Path root) {
        if (!Files.isDirectory(root)) throw new SyncException("Not a directory: "+root);
        Map<String,String> r = new LinkedHashMap<>();
        try (var s = Files.walk(root)) { s.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".md")).filter(p->!RESERVED.contains(p.getFileName().toString())).filter(p->{String rel=root.relativize(p).toString();return !IGNORE.matcher(rel).find();}).sorted().forEach(p->{r.put(root.relativize(p).toString(),sha256(p));}); }
        catch (IOException e) { throw new SyncException("Scan failed: "+root,e); }
        return r;
    }
    private String sha256(Path p) { try { byte[] b = Files.readAllBytes(p); MessageDigest md = MessageDigest.getInstance("SHA-256"); return HexFormat.of().formatHex(md.digest(b)); } catch(Exception e) { throw new SyncException("Hash failed: "+p,e); } }
}
