package com.heirloom.knowledge.sync;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
public class KnowledgeSyncEngine {
    private final KnowledgeArticleRepository articleRepo;
    private final FileScanner fileScanner = new FileScanner();
    private final FrontmatterParser parser = new FrontmatterParser();
    private SyncDiff lastDiff;
    public KnowledgeSyncEngine(KnowledgeArticleRepository r) { articleRepo = r; }
    public SyncDiff getLastDiff() { return lastDiff; }
    public SyncReport sync(KnowledgeSource source) {
        SyncReport report = SyncReport.start(source.getFullyQualifiedName());
        Path root = Path.of(source.getPath());
        Map<String,String> currentHashes;
        try { currentHashes = fileScanner.scan(root); } catch(Exception e) { report.addError("","Scan: "+e.getMessage(),"SCAN_ERROR"); report.complete(); return report; }
        report.setTotalFiles(currentHashes.size());
        Map<String,String> indexed = articleRepo.getIndexedFileHashes(source.getFullyQualifiedName());
        SyncDiff diff = computeDiff(currentHashes, indexed);
        this.lastDiff = diff;
        report.setSkipped(diff.unchangedFiles().size());
        List<String> toProcess = new ArrayList<>(); toProcess.addAll(diff.newFiles()); toProcess.addAll(diff.changedFiles()); toProcess.addAll(diff.recreatedFiles());
        for (String fp : toProcess) {
            try {
                String content = Files.readString(root.resolve(fp));
                ParseResult parsed = parser.parse(content, fp);
                KnowledgeArticle a = buildArticle(parsed, source, fp, currentHashes.get(fp));
                articleRepo.syncUpsert(a);
                if (parsed.hasErrors() || !parsed.hasType()) report.addError(fp, parsed.hasErrors() ? parsed.errors().get(0).message() : "Missing type", parsed.hasErrors() ? "PARSE_ERROR" : "MISSING_TYPE");
                if (diff.newFiles().contains(fp)) report.incrementCreated();
                else if (diff.recreatedFiles().contains(fp)) report.incrementCreated();
                else report.incrementUpdated();
            } catch(IOException e) { report.addError(fp, "Read: "+e.getMessage(), "READ_ERROR"); }
            catch(Exception e) { report.addError(fp, e.getMessage()!=null?e.getMessage():"Unknown", "PROCESS_ERROR"); }
        }
        for (String fp : diff.removedFiles()) {
            try { articleRepo.findByFilePath(source.getFullyQualifiedName(), fp).ifPresent(a->{a.setDeleted(true);a.setUpdatedAt(Instant.now());articleRepo.syncUpsert(a);}); report.incrementRemoved(); }
            catch(Exception e) { report.addError(fp, "Remove: "+e.getMessage(), "REMOVE_ERROR"); }
        }
        report.complete(); return report;
    }
    private KnowledgeArticle buildArticle(ParseResult p, KnowledgeSource src, String fp, String hash) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setName(derivedName(fp)); a.setFilePath(fp); a.setFileHash(hash); a.setSourceFqn(src.getFullyQualifiedName());
        a.setBody(p.body()); a.setFrontmatterRaw(p.frontmatterRaw()); a.setFrontmatter(p.frontmatter());
        a.setType(Objects.toString(p.frontmatter().get("type"),"unknown"));
        a.setTitle(Objects.toString(p.frontmatter().get("title"),"Untitled"));
        a.setDescription(Objects.toString(p.frontmatter().get("description"),null));
        a.setResource(Objects.toString(p.frontmatter().get("resource"),null));
        a.setTags((List<String>)p.frontmatter().getOrDefault("tags",List.of()));
        a.setStatus(Objects.toString(p.frontmatter().get("status"),"published"));
        a.setDomain(src.getName()!=null?src.getName():"default");
        a.setAuthor(Objects.toString(p.frontmatter().get("x_author"),null));
        a.setOkfVersion("0.1"); a.setLastSyncedAt(Instant.now());
        if (p.hasErrors()) { a.setSyncStatus("PARSE_ERROR"); a.setSyncError(p.errors().get(0).message()); }
        else if (!p.hasType()) { a.setSyncStatus("MISSING_TYPE"); a.setSyncError("Missing type"); }
        else a.setSyncStatus("OK");
        return a;
    }
    private String derivedName(String fp) { if(fp==null)return"untitled"; String n=fp.contains("/")?fp.substring(fp.lastIndexOf('/')+1):fp; return n.replaceAll("\\.md$",""); }
    SyncDiff computeDiff(Map<String,String> cur, Map<String,String> idx) {
        List<String> nf=new ArrayList<>(),cf=new ArrayList<>(),uf=new ArrayList<>(),rf=new ArrayList<>(),rec=new ArrayList<>();
        for (String p : cur.keySet()) { String ch=cur.get(p),ih=idx.get(p); if(ih==null)nf.add(p); else if(!ch.equals(ih))cf.add(p); else uf.add(p); }
        for (String p : idx.keySet()) if(!cur.containsKey(p)) rf.add(p);
        for (String p : nf) if(rf.contains(p)) rec.add(p);
        nf.removeAll(rec); rf.removeAll(rec);
        return new SyncDiff(nf,cf,rf,uf,rec,cur.size());
    }
}
