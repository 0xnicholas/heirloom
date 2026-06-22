package com.heirloom.knowledge.sync;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import java.util.*;
public class FrontmatterParser {
    private static final Yaml YAML = new Yaml();
    public ParseResult parse(String content, String filePath) {
        List<ParseError> errors = new ArrayList<>();
        Map<String,Object> fm = new HashMap<>();
        String body = content, raw = "";
        if (content == null || content.isBlank()) { errors.add(new ParseError("Empty","MISSING_TYPE")); return new ParseResult(fm,body,raw,errors); }
        String t = content.stripLeading();
        if (!t.startsWith("---")) { errors.add(new ParseError("Missing frontmatter","MISSING_TYPE")); return new ParseResult(fm,content,raw,errors); }
        int ci = t.indexOf("---",3);
        if (ci < 0) { errors.add(new ParseError("Unclosed","UNCLOSED_FRONTMATTER")); return new ParseResult(fm,t.substring(3),raw,errors); }
        raw = t.substring(3,ci).strip(); body = t.substring(ci+3).strip();
        Map<String,Object> r;
        try { Object p = YAML.load(raw); r = p instanceof Map<?,?> m ? (Map<String,Object>)m : new HashMap<>(); }
        catch (YAMLException e) { errors.add(new ParseError(e.getMessage(),"PARSE_ERROR")); return new ParseResult(fm,body,raw,errors); }
        fm.put("type",stringOrNull(r.remove("type"))); fm.put("title",stringOrNull(r.remove("title")));
        fm.put("description",stringOrNull(r.remove("description"))); fm.put("tags",normalizeTags(r.remove("tags")));
        fm.put("resource",stringOrNull(r.remove("resource"))); fm.put("timestamp",stringOrNull(r.remove("timestamp")));
        fm.put("status",stringOrNull(r.remove("status")));
        for (var e : r.entrySet()) fm.put("x_"+e.getKey(), e.getValue());
        if (fm.get("type") == null || fm.get("type").toString().isBlank()) { errors.add(new ParseError("Missing type","MISSING_TYPE")); fm.remove("type"); }
        if (fm.get("title") == null || fm.get("title").toString().isBlank()) fm.put("title", deriveTitle(filePath));
        return new ParseResult(fm,body,raw,errors);
    }
    public static List<String> normalizeTags(Object raw) {
        if (raw == null) return new ArrayList<>(); if (raw instanceof String s) return s.isBlank()?new ArrayList<>():List.of(s.strip());
        if (raw instanceof List<?> l) return l.stream().filter(Objects::nonNull).map(Object::toString).map(String::strip).filter(x->!x.isBlank()).toList();
        return new ArrayList<>();
    }
    public static String deriveTitle(String fp) {
        if (fp == null||fp.isBlank()) return "Untitled";
        String fn = fp.contains("/")?fp.substring(fp.lastIndexOf('/')+1):fp;
        String nm = fn.replaceAll("\\.md$",""); String[] ws = nm.split("[-_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String w : ws) { if(w.isBlank())continue; if(sb.length()>0)sb.append(' '); sb.append(Character.toUpperCase(w.charAt(0))); if(w.length()>1)sb.append(w.substring(1)); }
        return sb.isEmpty()?"Untitled":sb.toString();
    }
    private static String stringOrNull(Object o) {
        if (o==null) return null; if (o instanceof java.util.Date d) return java.time.Instant.ofEpochMilli(d.getTime()).toString();
        String s=o.toString().strip(); return s.isEmpty()?null:s;
    }
}
