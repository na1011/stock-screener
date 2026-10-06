import java.nio.file.*; import java.util.*;
public class P { public static void main(String[] a) throws Exception {
  var m = new tools.jackson.databind.ObjectMapper();
  List<Path> fs; try (var s = Files.list(Path.of(a[0]))) { fs = s.filter(p->p.toString().endsWith(".json")).sorted().toList(); }
  // warm-up
  for (int i=0;i<20;i++) m.readTree(Files.readString(fs.get(i)));
  long total=0, bytes=0, max=0; String maxf="";
  for (Path p: fs) { String s=Files.readString(p); long t=System.nanoTime(); m.readTree(s); long d=System.nanoTime()-t; total+=d; bytes+=s.length(); if(d>max){max=d;maxf=p.getFileName().toString();} }
  System.out.printf("files=%d chars=%.1fM parse-once total=%.1fs max=%.0fms(%s)%n", fs.size(), bytes/1e6, total/1e9, max/1e6, maxf);
}}
