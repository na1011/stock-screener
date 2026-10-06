import java.util.*;
/** 결정 2의 술어(ProcessHandle로 argv `--job=` · java)가 이 기계에서 무엇을 보고 무엇을 못 보는가. 읽기만. */
public class PhProbe {
    public static void main(String[] a) {
        long self = ProcessHandle.current().pid();
        String me = System.getProperty("user.name");
        int total=0, noCmd=0, noArgs=0, noUser=0, mine=0, mineNoCmd=0, mineNoArgs=0, javaMine=0, javaMineNoArgs=0, jobHits=0;
        List<String> javaLines = new ArrayList<>();
        for (ProcessHandle p : (Iterable<ProcessHandle>) ProcessHandle.allProcesses()::iterator) {
            total++;
            ProcessHandle.Info i = p.info();
            Optional<String> cmd = i.command(); Optional<String[]> args = i.arguments(); Optional<String> user = i.user();
            if (cmd.isEmpty()) noCmd++;
            if (args.isEmpty()) noArgs++;
            if (user.isEmpty()) noUser++;
            boolean isMine = user.map(me::equals).orElse(false);
            if (isMine) { mine++; if (cmd.isEmpty()) mineNoCmd++; if (args.isEmpty()) mineNoArgs++; }
            boolean isJava = cmd.map(c -> c.endsWith("/java")).orElse(false);
            if (isJava && p.pid() != self) {
                if (isMine) javaMine++;
                if (args.isEmpty()) javaMineNoArgs++;
                String joined = args.map(x -> String.join(" ", x)).orElse("<args 없음>");
                boolean hit = joined.contains("--job=") || joined.matches(".*spring\\.profiles\\.active=\\S*bootstrap.*");
                if (hit) jobHits++;
                javaLines.add(p.pid() + " user=" + user.orElse("?") + " hit=" + hit + " argsLen=" + args.map(x -> x.length).orElse(-1)
                        + " head=" + (joined.length() > 140 ? joined.substring(0, 140) : joined));
            }
        }
        System.out.printf("전체 %d · command 없음 %d · arguments 없음 %d · user 없음 %d%n", total, noCmd, noArgs, noUser);
        System.out.printf("내 사용자(%s) %d · 그중 command 없음 %d · arguments 없음 %d%n", me, mine, mineNoCmd, mineNoArgs);
        System.out.printf("java(자기 제외) %d · arguments 없음 %d · --job=/bootstrap 매치 %d%n", javaLines.size(), javaMineNoArgs, jobHits);
        for (String l : javaLines) System.out.println("  " + l);
    }
}
